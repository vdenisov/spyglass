import { ref, onBeforeUnmount } from 'vue'
import { storageKey } from './config.js'
import { loadJson, saveJson } from './storage.js'

// The update check: one timer polls the spec, and a sustained change (confirmed across a Blue/Green-safe
// window) raises a single dismissible "<API> was updated" toast. The detector is a content hash of the
// spec text — the common case is a regenerated spec (new endpoints/schemas), and a hash catches every
// such change even when the spec's info.version is static (the typical microservice). Reload is always
// user-initiated (a reload would drop unsaved per-operation form input), never automatic.
//
// Lifecycle: called synchronously from App's setup() so onBeforeUnmount registers while a component
// instance is active, then wired up via start() once the spec has loaded. The returned `show` ref drives
// <UpdateToast>; reload()/dismiss() are its button handlers.

const MAX_BACKOFF_LEVEL = 4   // error backoff doubles the delay per consecutive failure, up to…
const MAX_BACKOFF_FACTOR = 8  // …this multiple of the poll interval.
const MAX_DISMISSED = 50      // cap on persisted dismissed change-hashes (FIFO eviction); ample in practice.

// cyrb53: a fast non-cryptographic hash, ample for "did the spec change?" (we only need different bytes
// to yield a different value, not collision resistance against an adversary). Avoids a SubtleCrypto
// dependency, so it also works on insecure origins.
function hashString(str) {
  let h1 = 0xdeadbeef
  let h2 = 0x41c6ce57
  for (let i = 0; i < str.length; i++) {
    const ch = str.charCodeAt(i)
    h1 = Math.imul(h1 ^ ch, 2654435761)
    h2 = Math.imul(h2 ^ ch, 1597334677)
  }
  h1 = Math.imul(h1 ^ (h1 >>> 16), 2246822507)
  h1 ^= Math.imul(h2 ^ (h2 >>> 13), 3266489909)
  h2 = Math.imul(h2 ^ (h2 >>> 16), 2246822507)
  h2 ^= Math.imul(h1 ^ (h1 >>> 13), 3266489909)
  return (4294967296 * (2097151 & h2) + (h1 >>> 0)).toString(36)
}

export function useUpdateCheck() {
  const show = ref(false)

  let specUrl = ''
  let loaded = null        // baseline hash (what this tab loaded)
  let lastEtag = null      // last spec ETag seen, replayed as If-None-Match
  let lastValue = null     // last hash computed (returned unchanged on a 304)

  // The current deviation from the loaded spec: the latest observed value that differs from `loaded`
  // (null means we're seeing the loaded value), and when that value was first observed. A change must be
  // *stable* — the same differing value, sustained for the whole window — before it surfaces, so the
  // toast tracks one concrete change at a time.
  let current = null
  let windowStart = 0

  // Dismissals, persisted as a bounded, insertion-ordered list of dismissed change-hashes (most-recent
  // last), so a dismissed change stays dismissed across reloads until a *different* one shows up — each
  // subsequent distinct change re-prompts on its own. Capped at MAX_DISMISSED with FIFO eviction so a
  // frequently-regenerated spec can't grow it without bound. A list (not an object map) keeps reliable
  // order even for all-digit hashes, which as object keys would reorder. Deliberately not in storage.js's
  // CLEARABLE set, so "Clear headers" leaves it alone.
  const DISMISSED_KEY = storageKey('update-dismissed')
  const storedDismissed = loadJson(localStorage, DISMISSED_KEY, [])
  let dismissed = Array.isArray(storedDismissed) ? storedDismissed : []

  let intervalMs = 0
  let confirmWindowMs = 0
  let timer = null
  let abort = null
  let started = false
  let running = false
  let backoffLevel = 0

  // Fold one observed value into the window:
  //   - equal to loaded → clear the deviation (your version is still in rotation, or a canary rolled back);
  //   - a different value than the one we're tracking → a new deviation: track it and (re)start the window;
  //   - the same value we're already tracking → sustained, keep the window running.
  // Restarting the window on every *change* means a transient blip never surfaces, and an in-progress
  // rollout (or a spec with volatile content) only surfaces once it settles on a single stable value.
  function updateWindow(v) {
    if (v === loaded) {
      current = null
    } else if (v !== current) {
      current = v
      windowStart = Date.now()
    }
  }

  const surfaced = () => current !== null && (Date.now() - windowStart) >= confirmWindowMs

  const isDismissed = () => current !== null && dismissed.includes(current)

  // Show the toast once a change is confirmed and not dismissed. Latches: once shown it stays until the
  // user acts (retract-on-rollback is intentionally out of scope). The toast never steals focus or shifts
  // layout (a fixed-position role=status node), so there's no need to defer it around active work.
  function evaluate() {
    if (show.value) return
    if (surfaced() && !isDismissed()) show.value = true
  }

  function dismiss() {
    if (current !== null && !dismissed.includes(current)) {
      dismissed.push(current)
      if (dismissed.length > MAX_DISMISSED) dismissed = dismissed.slice(-MAX_DISMISSED)
      saveJson(localStorage, DISMISSED_KEY, dismissed)
    }
    show.value = false
  }

  function reload() {
    window.location.reload()
  }

  // Poll the spec. Replays the last ETag as an opportunistic If-None-Match: a host running a validator
  // (e.g. ShallowEtagHeaderFilter) answers an unchanged poll with a cheap 304 (we return the last hash);
  // otherwise we hash the 200 body. cache:'no-store' keeps the browser out of the loop, and because we
  // set If-None-Match ourselves a 304 is surfaced as-is rather than synthesized into a 200-from-cache.
  async function probe(signal) {
    const headers = { Accept: 'application/json' }
    if (lastEtag) headers['If-None-Match'] = lastEtag
    const res = await fetch(specUrl, { cache: 'no-store', signal, headers })
    if (res.status === 304) return lastValue
    if (!res.ok) throw new Error(`spec HTTP ${res.status}`)
    const text = await res.text()
    lastValue = hashString(text)
    lastEtag = res.headers.get('ETag')
    return lastValue
  }

  function schedule(ms) {
    clearTimeout(timer)
    timer = setTimeout(tick, ms)
  }

  function scheduleNext(err) {
    if (!started) return
    if (document.visibilityState !== 'visible') { backoffLevel = 0; schedule(intervalMs); return }
    if (err) {
      backoffLevel = Math.min(backoffLevel + 1, MAX_BACKOFF_LEVEL)
      schedule(Math.min(intervalMs * (2 ** backoffLevel), intervalMs * MAX_BACKOFF_FACTOR))
    } else {
      backoffLevel = 0
      schedule(intervalMs)
    }
  }

  async function tick() {
    if (running) return
    running = true
    let err = false
    try {
      // Gated on visibility: a hidden tab does no network at all, just reschedules.
      if (document.visibilityState === 'visible') {
        try { updateWindow(await probe(abort.signal)) } catch (e) { err = true }
        evaluate()
      }
    } finally {
      running = false
    }
    scheduleNext(err)
  }

  function onVisibility() {
    if (document.visibilityState === 'visible' && started) {
      backoffLevel = 0
      schedule(0)
    }
  }

  // Begin polling. No-op unless enabled and given a spec URL + the loaded spec text (the baseline). The
  // baseline is hashed from the exact bytes loadSpec already read, so it's tied to the document this tab
  // rendered. Idempotent.
  function start({ config, specUrl: url, loadedText, loadedEtag } = {}) {
    if (started || !config || !config.enabled || !url || loadedText == null) return
    started = true
    specUrl = url
    loaded = hashString(loadedText)
    lastValue = loaded
    lastEtag = loadedEtag || null
    intervalMs = Math.max(0, config.intervalSeconds * 1000)
    confirmWindowMs = Math.max(0, config.confirmWindowSeconds * 1000)
    abort = new AbortController()
    document.addEventListener('visibilitychange', onVisibility)
    schedule(intervalMs)
  }

  function stop() {
    started = false
    clearTimeout(timer)
    if (abort) abort.abort()
    document.removeEventListener('visibilitychange', onVisibility)
  }

  onBeforeUnmount(stop)

  return { show, start, reload, dismiss }
}
