// Low-level IndexedDB store for the Request Log (the record-building/sanitizing layer that sits on top
// lives in requestLog.js). One database per origin, namespaced by the configurable storage prefix
// (config.js): a `records` object store of immutable request/response records plus a tiny `meta` store
// holding a running byte total. This is deliberately separate from storage.js (localStorage, small
// key/value pairs): a request log can grow large, so it gets IndexedDB's own origin-scoped quota rather
// than competing with the ~5 MB localStorage pool the form drafts and UI state already use.
//
// Graceful absence: if IndexedDB is unavailable or blocked, openDb() resolves to a DISABLED sentinel and
// every operation becomes a safe no-op (writes drop, reads return empty) — the feature disables rather
// than errors. Every export is reject-safe: a failure is reported to the console and swallowed, never
// propagated to the caller (logging must not break the request that triggered it).
//
// Ordering: the auto-increment `id` is the FIFO key everywhere. It increases monotonically and is never
// reused, so insertion order equals key order regardless of wall-clock resolution or same-millisecond
// collisions; eviction always removes the lowest ids (the oldest). The stored `ts` is for display only.

import { storageKey, REQUEST_LOG_DEFAULTS } from './config.js'

const DB_NAME = storageKey('request-log')
const STORE = 'records'
const META = 'meta'
const TOTALS_KEY = 'totals'   // the singleton meta row: { k: 'totals', bytes }
// Compound [opId, id] index: lets a cursor walk one operation's records in insertion order (for the
// per-operation cap) and backs per-operation reads. IDBKeyRange.bound([opId], [opId, []]) spans all of
// an operation's keys — an array sorts after any number, so [opId, []] is greater than every [opId, id].
const OP_INDEX = 'opId_id'

// Capacity bounds — mutable so the host can retune them through the config seam (config.js → App.js →
// configureRequestLog → configureStore), seeded from the single source of truth in config.js so the
// fallbacks can't drift from the documented defaults. Replaced by configureStore; an unset/invalid
// layer leaves the seeded default in place.
let PER_OP_CAP = REQUEST_LOG_DEFAULTS.perOpCap            // primary bound: entries kept per operation
let GLOBAL_COUNT_CAP = REQUEST_LOG_DEFAULTS.globalCountCap // total entries across all operations
let GLOBAL_BYTES_CAP = REQUEST_LOG_DEFAULTS.globalBytesCap // ~5 MB of stored record footprint

// Applies host-tuned capacity bounds. Each is taken only when a positive integer, so a missing or
// malformed value keeps the default (config.js already validates, this is belt-and-braces).
export function configureStore({ perOpCap, globalCountCap, globalBytesCap } = {}) {
  if (Number.isInteger(perOpCap) && perOpCap > 0) PER_OP_CAP = perOpCap
  if (Number.isInteger(globalCountCap) && globalCountCap > 0) GLOBAL_COUNT_CAP = globalCountCap
  if (Number.isInteger(globalBytesCap) && globalBytesCap > 0) GLOBAL_BYTES_CAP = globalBytesCap
}

// "IndexedDB unusable" sentinel — see graceful absence above.
const DISABLED = Symbol('request-log-disabled')

let dbPromise = null

// Opens (once) the request-log database, resolving to the connection or the DISABLED sentinel. Never
// rejects: any failure to open degrades the feature to a no-op rather than surfacing an error.
function openDb() {
  if (dbPromise) return dbPromise
  dbPromise = new Promise((resolve) => {
    let idb
    try { idb = window.indexedDB } catch (e) { idb = null }
    if (!idb) { resolve(DISABLED); return }
    let req
    try { req = idb.open(DB_NAME, 1) } catch (e) { resolve(DISABLED); return }
    req.onupgradeneeded = () => {
      const db = req.result
      const store = db.createObjectStore(STORE, { keyPath: 'id', autoIncrement: true })
      store.createIndex(OP_INDEX, ['opId', 'id'])
      db.createObjectStore(META, { keyPath: 'k' })
    }
    req.onsuccess = () => resolve(req.result)
    req.onerror = () => resolve(DISABLED)
    req.onblocked = () => resolve(DISABLED)
  })
  return dbPromise
}

// Awaits a single IDBRequest's result.
function promisify(request) {
  return new Promise((resolve, reject) => {
    request.onsuccess = () => resolve(request.result)
    request.onerror = () => reject(request.error)
  })
}

// Awaits a transaction's COMMIT (not just a request's success): a put can fire success before the
// transaction commits, and quota failures surface as the transaction aborting — so the write isn't
// durable, and quota isn't observable, until oncomplete/onabort.
function txDone(tx) {
  return new Promise((resolve, reject) => {
    tx.oncomplete = () => resolve()
    tx.onabort = () => reject(tx.error)
    tx.onerror = () => reject(tx.error)
  })
}

// Resolves the db, opens a transaction, runs fn(tx), awaits the commit, and returns fn's result —
// degrading to `fallback` when IndexedDB is disabled and swallowing any error (best-effort contract).
// putRecord doesn't use this: it owns a bounded quota-retry loop spanning several transactions.
async function withTx(stores, mode, fallback, fn) {
  const db = await openDb()
  if (db === DISABLED) return fallback
  try {
    const tx = db.transaction(stores, mode)
    const result = await fn(tx)
    await txDone(tx)
    return result
  } catch (e) {
    console.error('[spyglass] request-log store operation failed:', e)
    return fallback
  }
}

function isQuota(e) {
  return !!e && e.name === 'QuotaExceededError'
}

// The key range spanning every record of one operation on the [opId, id] index (see OP_INDEX above).
function opRange(opId) {
  return IDBKeyRange.bound([opId], [opId, []])
}

// Adds `delta` to the running byte total (floored at 0) and returns the new total. Updated in the same
// transaction as the records it accounts for, so it can't drift: a tx abort rolls back both together.
async function adjustTotal(meta, delta) {
  const row = await promisify(meta.get(TOTALS_KEY))
  const bytes = Math.max(0, (row ? row.bytes : 0) + delta)
  meta.put({ k: TOTALS_KEY, bytes })
  return bytes
}

// Deletes up to `n` records from the front of a cursor (oldest-first when the cursor walks ascending),
// returning the total `bytes` removed. Pass Infinity to drain the whole range.
function deleteOldest(cursorRequest, n) {
  return new Promise((resolve, reject) => {
    let remaining = n
    let removed = 0
    cursorRequest.onsuccess = () => {
      const cursor = cursorRequest.result
      if (!cursor || remaining <= 0) { resolve(removed); return }
      removed += (cursor.value.bytes || 0)
      cursor.delete()
      remaining--
      cursor.continue()
    }
    cursorRequest.onerror = () => reject(cursorRequest.error)
  })
}

// Deletes oldest records until at least `overBy` bytes have been freed, returning the bytes removed.
// Walks only the records it evicts (no full-store scan).
function deleteUntilFreed(store, overBy) {
  return new Promise((resolve, reject) => {
    let removed = 0
    const c = store.openCursor()
    c.onsuccess = () => {
      const cur = c.result
      if (!cur || removed >= overBy) { resolve(removed); return }
      removed += (cur.value.bytes || 0)
      cur.delete()
      cur.continue()
    }
    c.onerror = () => reject(c.error)
  })
}

// Trims one operation's records down to the per-operation cap, oldest first, returning bytes removed.
// Only the just-written operation can have grown, so the caller passes its opId.
async function trimPerOp(store, opId) {
  const index = store.index(OP_INDEX)
  const range = opRange(opId)
  const total = await promisify(index.count(range))
  return total > PER_OP_CAP ? deleteOldest(index.openCursor(range), total - PER_OP_CAP) : 0
}

// Trims the whole store down to the global entry cap, oldest first, returning bytes removed.
async function trimGlobalCount(store) {
  const total = await promisify(store.count())
  return total > GLOBAL_COUNT_CAP ? deleteOldest(store.openCursor(), total - GLOBAL_COUNT_CAP) : 0
}

// One put plus its proactive eviction, all in a single readwrite transaction so the store is never
// observed over a cap and overlapping writes (concurrent sends) serialize against each other. The byte
// total is kept live in the `meta` store as we go, so the byte cap is enforced from a counter rather
// than a full scan. Only IDB requests are awaited between operations — awaiting an unrelated promise
// here would let the transaction auto-commit early.
async function writeOnce(db, record) {
  const tx = db.transaction([STORE, META], 'readwrite')
  const store = tx.objectStore(STORE)
  const meta = tx.objectStore(META)
  store.add(record)
  const perOpRemoved = await trimPerOp(store, record.opId)
  const countRemoved = await trimGlobalCount(store)
  let total = await adjustTotal(meta, (record.bytes || 0) - perOpRemoved - countRemoved)
  if (total > GLOBAL_BYTES_CAP) {
    const removed = await deleteUntilFreed(store, total - GLOBAL_BYTES_CAP)
    await adjustTotal(meta, -removed)
  }
  await txDone(tx)
}

// Deletes the `n` globally-oldest records in their own transaction (and decrements the byte total).
// Used to free space after a quota abort — the aborting transaction is already rolled back, so the
// retry needs a fresh one.
async function deleteOldestBatch(db, n) {
  const tx = db.transaction([STORE, META], 'readwrite')
  const removed = await deleteOldest(tx.objectStore(STORE).openCursor(), n)
  await adjustTotal(tx.objectStore(META), -removed)
  await txDone(tx)
}

// Persists one record (with proactive eviction). Best-effort: a non-quota failure is swallowed; on a
// QuotaExceededError it frees space in bounded escalating batches and retries, then gives up silently —
// logging is never on the request's critical path.
export async function putRecord(record) {
  const db = await openDb()
  if (db === DISABLED) return
  try {
    await writeOnce(db, record)
    return
  } catch (e) {
    if (!isQuota(e)) { console.error('[spyglass] request-log write failed:', e); return }
  }
  for (const batch of [1, 5, 10]) {
    try {
      await deleteOldestBatch(db, batch)
      await writeOnce(db, record)
      return
    } catch (e) {
      if (!isQuota(e)) { console.error('[spyglass] request-log write failed:', e); return }
    }
  }
  console.error('[spyglass] request-log write gave up after repeated QuotaExceededError; record dropped')
}

// One operation's records, oldest first (insertion order). Empty when the store is disabled or on error.
export async function getByOpId(opId) {
  return withTx(STORE, 'readonly', [], (tx) =>
    promisify(tx.objectStore(STORE).index(OP_INDEX).getAll(opRange(opId))))
}

// Every record across all operations, oldest first. Empty when disabled or on error.
export async function getAll() {
  return withTx(STORE, 'readonly', [], (tx) =>
    promisify(tx.objectStore(STORE).getAll()))
}

// Removes a single record by its auto-increment id (and decrements the byte total). Best-effort.
export async function deleteRecord(id) {
  return withTx([STORE, META], 'readwrite', undefined, async (tx) => {
    const store = tx.objectStore(STORE)
    const row = await promisify(store.get(id))
    if (!row) return
    store.delete(id)
    await adjustTotal(tx.objectStore(META), -(row.bytes || 0))
  })
}

// Clears one operation's records (opId given) or the whole store (opId omitted), keeping the byte total
// in step. Best-effort.
export async function clear(opId) {
  return withTx([STORE, META], 'readwrite', undefined, async (tx) => {
    const store = tx.objectStore(STORE)
    const meta = tx.objectStore(META)
    if (opId == null) {
      store.clear()
      meta.put({ k: TOTALS_KEY, bytes: 0 })
    } else {
      const removed = await deleteOldest(store.index(OP_INDEX).openCursor(opRange(opId)), Infinity)
      await adjustTotal(meta, -removed)
    }
  })
}
