// Request Log capture layer: turns a completed request/response into an immutable, sanitized,
// size-bounded record and hands it to the IndexedDB store (requestLogStore.js). Called fire-and-forget
// from the execute path (App.js wires it onto OperationPanel's onExecuted), so it must never throw or
// reject back to the caller and must stay off the render path.
//
// Two invariants ride here:
//   - Write-time, fail-closed sanitization: secrets are redacted BEFORE anything is persisted, and if a
//     sanitizer throws, the record is DROPPED rather than written un-redacted. (Contrast the header-link
//     resolver hook in extensions.js, where a throw safely means "no link" — here the safe default is
//     "don't store," because the risk is leaking a secret to disk.)
//   - Frugal storage: a large/binary body is not stored verbatim — it's replaced by a small `bodyInfo`
//     descriptor (kind, byte count, content type, filename; file CONTENTS never stored), and a multipart
//     request by a structured parts list. The response `size` still reflects the true byte count. A
//     stored `body` is therefore always the real text; when it's absent, `bodyInfo` describes what was
//     elided (and `body`/`bodyInfo` are never both present).
//
// Records are snapshots of what was actually sent/received, bound to their operation by opId
// ("<METHOD> <path>") — never re-derived from the live spec, so a later schema change can't corrupt a
// stored entry. The `params` field holds the form snapshot used to repopulate the form on replay; this
// layer only stores it.
//
// Only completed responses are captured (the seam fires after a response is built): a 4xx/5xx is logged
// like any other response. Transport failures (DNS/timeout/connection refused) and user cancels are
// not logged — they carry no status/body to record.

import { filenameFromDisposition } from './format.js'
import { REQUEST_LOG_DEFAULTS } from './config.js'
import { putRecord, getByOpId, getAll, deleteRecord, clear, configureStore } from './requestLogStore.js'

// Bodies at/under this UTF-8 byte size are stored verbatim; larger ones are elided to a bodyInfo
// descriptor. Mutable so the host can retune it via the config seam (configureRequestLog); seeded from
// the single source of truth in config.js.
let BODY_CAP = REQUEST_LOG_DEFAULTS.bodyCap

// Whether capture is enabled. On by default (justified by write-time sanitization); a host disables it
// through the config seam for kiosk / shared machines, after which recordExecution writes nothing.
let enabled = REQUEST_LOG_DEFAULTS.enabled

const encoder = new TextEncoder()

// Applies the resolved Request Log config (config.js → resolveRequestLogConfig). Sets the enable gate
// and the body-truncation threshold here, and forwards the storage caps to the store. Called once at
// startup from App.js; the caps are read live, so this may also run before any capture. config.js has
// already validated, so the guards here are belt-and-braces.
export function configureRequestLog(config = {}) {
  if ('enabled' in config) enabled = !!config.enabled
  if (Number.isInteger(config.bodyCap) && config.bodyCap > 0) BODY_CAP = config.bodyCap
  configureStore(config)
}

// Ordered sanitizers, run on every record before persist. The core Authorization mask is always first;
// extensions append after it via registerSanitizer. Each receives the pre-persist record and returns it
// (redacted in place). A throw anywhere drops the record.
const sanitizers = [coreAuthSanitizer]

export function registerSanitizer(fn) {
  if (typeof fn === 'function') sanitizers.push(fn)
}

// Captures one completed execution. opId/req/snapshot are taken from the send site (before any await),
// response is the already-built es.response. Best-effort throughout: any failure is swallowed.
export async function recordExecution({ opId, req, response, snapshot } = {}) {
  try {
    // The single disable gate: when the feature is off nothing is captured, so no record ever reaches
    // disk (the panel is also hidden — see App.js). Checked first, before any record is assembled.
    if (!enabled) return
    if (!opId || !req || !response) return
    const reqContentType = req.bodyData instanceof FormData ? 'multipart/form-data' : headerValue(req.headers, 'content-type')
    const reqParts = requestBodyParts(req, reqContentType)
    const resp = responseParts(response)
    const record = {
      ts: Date.now(),
      opId,
      request: {
        method: req.method,
        url: req.absUrl || req.relUrl || '',
        params: snapshot || null,
        headers: { ...(req.headers || {}) },
        contentType: reqContentType || '',
        body: reqParts.body,
        bodyInfo: reqParts.bodyInfo
      },
      response: {
        status: response.status,
        statusText: response.statusText || '',
        size: resp.size,
        durationMs: response.durationMs,
        headers: headersFromList(response.headersList),
        contentType: response.contentType || '',
        body: resp.body,
        bodyInfo: resp.bodyInfo,
        finalUrl: response.finalUrl || ''
      }
    }
    const sanitized = runSanitizers(record)
    if (!sanitized) return
    // `bytes` is an approximate stored footprint (after sanitizing + truncation), used for the global
    // byte cap — deliberately small for a placeheld large/binary body, since the cap bounds disk, not
    // payloads. JSON length (chars) is a fine proxy for a ~5 MB fuzzy cap and accounts for the uncapped
    // replay snapshot.
    sanitized.bytes = JSON.stringify(sanitized).length
    await putRecord(sanitized)
  } catch (e) {
    // Capture must never break the request it logs, so building the record is best-effort — but a
    // failure here is a bug, so report it to the console for diagnosis. (Store-side failures are
    // reported by requestLogStore.js; this catch covers record assembly.)
    console.error('[spyglass] request-log capture failed:', e)
  }
}

// Read accessors for stored records, returned oldest-first (insertion order). recordsForOp scopes to a
// single operation; allRecords spans all. deleteRecord/clear are re-exported so a caller manages the
// whole log through this one module rather than reaching into the store directly.
export async function recordsForOp(opId) { return getByOpId(opId) }
export async function allRecords() { return getAll() }
export { deleteRecord, clear }

// Applies the sanitizer chain in order; returns the redacted record, or null if any sanitizer throws
// (fail-closed — drop rather than persist un-redacted). A throw is reported with the offending
// sanitizer and its error, but never the record itself: at the point of failure the record may still
// hold the unredacted secret the sanitizer was meant to strip.
function runSanitizers(record) {
  let r = record
  for (const fn of sanitizers) {
    try {
      r = fn(r) || r
    } catch (e) {
      console.error('[spyglass] request-log sanitizer failed; dropping record:', fn.name || '(anonymous)', e)
      return null
    }
  }
  return r
}

// Core default: masks the request Authorization header so a token never reaches disk. Sent with a value
// -> "***"; sent empty -> kept as an empty value (records that an empty header was sent); not sent ->
// absent (left out of the record). Case-insensitive: a user-added header row may be lower-cased.
function coreAuthSanitizer(record) {
  const headers = record.request && record.request.headers
  if (headers) {
    for (const name of Object.keys(headers)) {
      if (name.toLowerCase() === 'authorization') headers[name] = headers[name] ? '***' : ''
    }
  }
  return record
}

// The stored request body as { body, bodyInfo } (exactly one non-null, or both null for no body).
// A within-cap text body is kept verbatim (`body`); an over-cap text body is elided to a 'truncated'
// descriptor (the text never reaches disk). Multipart is described structurally (kind 'multipart' +
// parts) with file CONTENTS never stored. The descriptor is what the UI renders as a plain note.
function requestBodyParts(req, contentType) {
  if (req.bodyData instanceof FormData) return { body: null, bodyInfo: { kind: 'multipart', parts: multipartParts(req.bodyData) } }
  if (req.bodyStr == null) return { body: null, bodyInfo: null }
  return textBody(req.bodyStr, contentType)
}

// The stored response body as { size, body, bodyInfo }. size is the exact wire byte count (blob.size).
// A text body within the cap is kept verbatim; an over-cap text body becomes a 'truncated' descriptor
// and a binary/never-decoded body a 'binary' descriptor — both keeping a Content-Disposition filename
// when present. An empty body is { body: null, bodyInfo: null }.
function responseParts(response) {
  const size = response.blob ? response.blob.size : (response.rawBody != null ? encoder.encode(response.rawBody).length : 0)
  const filename = filenameFromDisposition(response.contentDisposition)
  const ct = response.contentType || ''
  if (response.rawBody != null) {
    const n = encoder.encode(response.rawBody).length
    if (n <= BODY_CAP) return { size, body: response.rawBody, bodyInfo: null }
    return { size, body: null, bodyInfo: { kind: 'truncated', bytes: size, contentType: ct, ...(filename ? { filename } : {}) } }
  }
  if (size) return { size, body: null, bodyInfo: { kind: 'binary', bytes: size, contentType: ct, ...(filename ? { filename } : {}) } }
  return { size, body: null, bodyInfo: null }
}

// A text body as { body, bodyInfo }: verbatim if within the cap, else a 'truncated' descriptor carrying
// the true byte count and content type (the text is dropped, never persisted).
function textBody(text, contentType) {
  const n = encoder.encode(text).length
  if (n <= BODY_CAP) return { body: text, bodyInfo: null }
  return { body: null, bodyInfo: { kind: 'truncated', bytes: n, contentType: contentType || '' } }
}

// Structures a multipart body into parts. A file part keeps name/size/type but NEVER its bytes; a text
// part keeps its value when within the cap, otherwise only its byte count.
function multipartParts(formData) {
  const parts = []
  for (const [name, value] of formData.entries()) {
    if (value instanceof File) {
      parts.push({ name, filename: value.name, bytes: value.size, contentType: value.type || 'application/octet-stream' })
    } else {
      const s = String(value)
      const n = encoder.encode(s).length
      parts.push(n <= BODY_CAP ? { name, value: s } : { name, bytes: n })
    }
  }
  return parts
}

// Case-insensitive header lookup over a plain {name: value} object.
function headerValue(headers, name) {
  if (!headers) return undefined
  const lower = name.toLowerCase()
  for (const k of Object.keys(headers)) if (k.toLowerCase() === lower) return headers[k]
  return undefined
}

// Turns es.response.headersList ([{name, value}]) into a plain {name: value} object for storage.
function headersFromList(list) {
  const out = {}
  if (Array.isArray(list)) for (const h of list) out[h.name] = h.value
  return out
}
