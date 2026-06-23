// Per-operation request-FORM snapshot, persisted to localStorage so a half-filled request survives a
// reload (and is restored when returning to an operation). Keyed by op.id ("<METHOD> <path>").
//
// Distinct from history.js: that is the capped recent-VALUES autocomplete store (a list per field);
// this is the single CURRENT snapshot per operation (params, media type, Form/Raw mode, the raw text,
// and the body as JSON). On reload the form is rebuilt from the schema and the saved JSON body is
// re-applied via spec.js `importValue` — the same translation the Form/Raw toggle uses — so nested
// objects, arrays, maps and oneOf/anyOf selections restore to whatever fidelity that path supports.
//
// Responses are deliberately NOT stored here: they can be large or sensitive, so the explorer keeps
// only the latest response per operation in memory (see App.js opStates), never on disk.

import { OP_FORM_KEY, loadJson, saveJson } from './storage.js'

// Cap on how many per-operation snapshots are kept. The whole map lives in one localStorage value, so
// without a bound it would grow with every operation ever visited (across sessions) until it hits the
// ~5 MB quota — after which saveJson silently drops ALL persistence. Re-inserting an entry on save makes
// the object's key order reflect write-recency, so the oldest (least-recently-saved) entries evict first.
const MAX_OPERATIONS = 50

function readAll() {
  const all = loadJson(localStorage, OP_FORM_KEY, {})
  return all && typeof all === 'object' ? all : {}
}

function writeAll(all) {
  saveJson(localStorage, OP_FORM_KEY, all)
}

// The saved snapshot for an operation, or null if none.
export function loadForm(opId) {
  const snap = readAll()[opId]
  return snap && typeof snap === 'object' ? snap : null
}

export function saveForm(opId, snapshot) {
  const all = readAll()
  delete all[opId]           // drop any prior entry so re-insertion moves this op to newest
  all[opId] = snapshot
  const ids = Object.keys(all)
  for (let i = 0; i < ids.length - MAX_OPERATIONS; i++) delete all[ids[i]] // evict oldest past the cap
  writeAll(all)
}

// Drops one operation's snapshot (its Reset button), leaving the others intact.
export function removeForm(opId) {
  const all = readAll()
  if (opId in all) {
    delete all[opId]
    writeAll(all)
  }
}
