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
//   - Frugal storage: large/binary bodies are truncated to a placeholder (file names kept, contents
//     never); the record's reported response `size` still reflects the true byte count.
//
// Records are snapshots of what was actually sent/received, bound to their operation by opId
// ("<METHOD> <path>") — never re-derived from the live spec, so a later schema change can't corrupt a
// stored entry. The `params` field holds the form snapshot used to repopulate the form on replay; this
// layer only stores it.
//
// Only completed responses are captured (the seam fires after a response is built): a 4xx/5xx is logged
// like any other response. Transport failures (DNS/timeout/connection refused) and user cancels are
// not logged — they carry no status/body to record.

import { formatBytes, filenameFromDisposition } from './format.js'
import { putRecord, getByOpId, getAll, deleteRecord, clear } from './requestLogStore.js'

// Bodies at/under this UTF-8 byte size are stored verbatim; larger ones become a byte-count placeholder.
const BODY_CAP = 32 * 1024

const encoder = new TextEncoder()

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
    if (!opId || !req || !response) return
    const reqContentType = req.bodyData instanceof FormData ? 'multipart/form-data' : headerValue(req.headers, 'content-type')
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
        body: requestBody(req, reqContentType)
      },
      response: {
        status: response.status,
        statusText: response.statusText || '',
        size: resp.size,
        durationMs: response.durationMs,
        headers: headersFromList(response.headersList),
        contentType: response.contentType || '',
        body: resp.body,
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

// The stored request body. Multipart is summarized part-by-part with file CONTENTS never stored (name +
// size + type only); any other body is text, truncated past the cap.
function requestBody(req, contentType) {
  if (req.bodyData instanceof FormData) return multipartSummary(req.bodyData)
  if (req.bodyStr == null) return null
  return truncateText(req.bodyStr, contentType)
}

// The stored response body plus its true size. size is the exact wire byte count (blob.size); the body
// is the decoded text when text-like and within the cap, otherwise a placeholder keeping a
// Content-Disposition filename when present.
function responseParts(response) {
  const size = response.blob ? response.blob.size : (response.rawBody != null ? encoder.encode(response.rawBody).length : 0)
  const name = filenameFromDisposition(response.contentDisposition)
  if (response.rawBody != null) {
    const n = encoder.encode(response.rawBody).length
    return { size, body: n <= BODY_CAP ? response.rawBody : placeholder(size, response.contentType, name) }
  }
  // Binary (never decoded) or empty.
  return { size, body: size ? placeholder(size, response.contentType, name) : '' }
}

// A text body: verbatim if within the cap, else a byte-count placeholder carrying the true size.
function truncateText(text, contentType) {
  if (text == null) return null
  const n = encoder.encode(text).length
  return n <= BODY_CAP ? text : placeholder(n, contentType)
}

// «N bytes of <contentType>» for an inline body, or «file "name", <size> of <contentType>» when a
// filename is known (uploads, downloads). The size keeps the log honest about how big the payload was.
function placeholder(n, contentType, name) {
  const of = contentType ? ' of ' + contentType : ''
  return name ? `«file "${name}", ${formatBytes(n)}${of}»` : `«${n} bytes${of}»`
}

// Summarizes a multipart body, one line per part. File parts keep name/size/type but never their bytes;
// text parts are kept verbatim unless they themselves exceed the body cap.
function multipartSummary(formData) {
  const parts = []
  for (const [name, value] of formData.entries()) {
    if (value instanceof File) {
      parts.push(`${name}: ${placeholder(value.size, value.type || 'application/octet-stream', value.name)}`)
    } else {
      const s = String(value)
      const n = encoder.encode(s).length
      parts.push(`${name}: ${n <= BODY_CAP ? s : placeholder(n, '')}`)
    }
  }
  return parts.join('\n')
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
