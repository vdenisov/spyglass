// Shareable request deep-links. Serializes the filled-in operation form (the same snapshot the Request
// Log replay path consumes) plus the non-secret global header rows into a compact, URL-safe blob that
// rides in the URL *fragment* (#...). The fragment is never sent to the server, so a shared link leaks
// nothing to the origin's logs; App.js rehydrates the form from it on load through the existing replay
// path (best-effort against the current schema, non-blocking warnings for drift).
//
// Two secret surfaces are kept out of a shared link:
//   - The Authorization header row (and any header param literally named Authorization). Its token is
//     ephemeral (sessionStorage) and must never travel in a URL a colleague pastes into chat.
//   - Any request field mapped to an apiKey securityScheme (a query/header/cookie key the spec declares
//     as auth — see securityApiKeyFields in spec.js). Bearer/basic/oauth2/openIdConnect all ride the
//     Authorization header, already excluded above.
// The exclusion is applied at payload-build time (buildSharePayload), so a secret never even reaches the
// encoder — not merely hidden behind compression.
//
// The wire format — fragment shape, the <codec><version> prefix, and the pruned payload keys — is
// specified in docs/deep-link-format.md, the authoritative contract (kept standalone so other tooling
// can produce compatible links). Change the format there first; this module implements it.

// The fragment separator between the operation anchor (#<METHOD>-<path>, owned by App.js) and the
// encoded state. '&' can't appear in an OpenAPI path template, so it never collides with the anchor.
export const STATE_SEP = '&s='

// Current payload format version (the second prefix character). Bump on a breaking payload change; a
// decoder rejects any version it doesn't recognize rather than misreading it.
const FORMAT_VERSION = '1'

// Parameter-location abbreviations used in payload param keys, and their inverse. Keeps the repeated
// "query:" / "header:" prefixes short in the small-payload regime (where compression can't help).
const IN_ABBREV = { path: 'p', query: 'q', header: 'h', cookie: 'c' }
const IN_EXPAND = { p: 'path', q: 'query', h: 'header', c: 'cookie' }

const hasCompression = typeof CompressionStream !== 'undefined' && typeof DecompressionStream !== 'undefined'

// --- codec -----------------------------------------------------------------

// Encodes a payload object to the URL-safe string: JSON → UTF-8 → (deflate-raw if it shrinks) → base64url,
// behind a two-char <codec><version> prefix. Async because CompressionStream is stream-based; the one
// await is paid once, on the Copy-link click.
export async function encodeState(obj) {
  const raw = new TextEncoder().encode(JSON.stringify(obj))
  if (hasCompression) {
    try {
      const deflated = await streamThrough(raw, new CompressionStream('deflate-raw'))
      // For a tiny payload DEFLATE can expand (framing/Huffman overhead); keep whichever is smaller so a
      // short request never pays for compression it didn't benefit from.
      if (deflated.length < raw.length) return 'D' + FORMAT_VERSION + bytesToBase64url(deflated)
    } catch (e) { /* fall through to the uncompressed path */ }
  }
  return 'R' + FORMAT_VERSION + bytesToBase64url(raw)
}

// Inverse of encodeState. Returns the decoded object, or null on any malformed input or unrecognized
// version — a bad, truncated, or future-format link is ignored (the operation still opens from its
// anchor), never thrown.
export async function decodeState(str) {
  try {
    if (typeof str !== 'string' || str.length < 2) return null
    const codec = str[0]
    if (str[1] !== FORMAT_VERSION) return null
    const bytes = base64urlToBytes(str.slice(2))
    let raw
    if (codec === 'D') {
      if (!hasCompression) return null
      raw = await streamThrough(bytes, new DecompressionStream('deflate-raw'))
    } else if (codec === 'R') {
      raw = bytes
    } else {
      return null
    }
    return JSON.parse(new TextDecoder().decode(raw))
  } catch (e) {
    return null
  }
}

// Absolute link to the current page carrying the encoded state in the fragment. The query string is
// preserved verbatim so the recipient loads the same config (spec, ns, ext); only the fragment carries
// the request, and the fragment never reaches the server. The anchor is raw (matching App.js's
// anchorFor), so applyHash decodes and splits it back the same way.
export function buildShareUrl(op, encoded) {
  const loc = window.location
  return loc.origin + loc.pathname + loc.search + '#' + op.method + '-' + op.path + STATE_SEP + encoded
}

// --- payload (prune + short keys + auth exclusion) -------------------------

// Whether a request parameter (path/query/header) is an auth field that must not travel in a link.
// Header names match case-insensitively (HTTP headers are case-insensitive); query/cookie names match
// exactly (they are case-sensitive). `fields` is the securityApiKeyFields() shape from spec.js.
export function isAuthParam(loc, name, fields) {
  if (loc === 'header') return name.toLowerCase() === 'authorization' || fields.header.has(name.toLowerCase())
  if (loc === 'query') return fields.query.has(name)
  if (loc === 'cookie') return fields.cookie.has(name)
  return false
}

// Whether a global header row is an auth row that must not travel in a link: the Authorization row, or a
// header the spec declares as an apiKey scheme.
export function isAuthHeaderRow(key, fields) {
  const k = (key || '').toLowerCase()
  return k === 'authorization' || fields.header.has(k)
}

// Builds the pruned deep-link payload from the operation form snapshot (buildSnapshot's output) and the
// global header rows, dropping every auth field so no secret reaches the encoder, and omitting every
// empty/default field so a trivial request stays short. Header rows are carried (as [name, value] pairs)
// so a request that needs a non-secret routing/sandbox/feature header reproduces from the link alone.
export function buildSharePayload(snap, headerRows, fields) {
  const w = {}
  const params = {}
  for (const [k, v] of Object.entries((snap && snap.params) || {})) {
    const i = k.indexOf(':')
    const loc = i < 0 ? '' : k.slice(0, i)
    const name = i < 0 ? k : k.slice(i + 1)
    if (isAuthParam(loc, name, fields)) continue
    const abbr = IN_ABBREV[loc] || loc
    params[(abbr ? abbr + ':' : '') + name] = v
  }
  if (Object.keys(params).length) w.p = params
  if (snap && snap.mediaType) w.m = snap.mediaType
  if (snap && snap.useRaw) { w.u = 1; if (snap.rawText) w.r = snap.rawText }
  if (snap && snap.body !== undefined) w.b = snap.body
  const headers = (headerRows || [])
    .filter(h => h && h.key && !isAuthHeaderRow(h.key, fields))
    .map(h => [h.key, h.value])
  if (headers.length) w.h = headers
  return w
}

// Expands a decoded payload back into the { snap, headers } the app consumes: the snapshot in
// buildSnapshot's shape (param keys restored to the canonical "<in>:<name>") and header rows as
// { key, value } objects. Returns null for a non-object payload. Defaults fill every field the pruned
// payload omitted.
export function expandSharePayload(w) {
  if (!w || typeof w !== 'object') return null
  const params = {}
  const wp = w.p && typeof w.p === 'object' ? w.p : {}
  for (const [k, v] of Object.entries(wp)) {
    const i = k.indexOf(':')
    const loc = i < 0 ? '' : k.slice(0, i)
    const name = i < 0 ? k : k.slice(i + 1)
    const full = IN_EXPAND[loc] || loc
    params[(full ? full + ':' : '') + name] = v
  }
  const snap = { params, mediaType: typeof w.m === 'string' ? w.m : '', useRaw: !!w.u, rawText: typeof w.r === 'string' ? w.r : '' }
  if ('b' in w) snap.body = w.b
  const headers = Array.isArray(w.h)
    ? w.h.filter(p => Array.isArray(p) && p.length).map(p => ({ key: p[0], value: p[1] != null ? p[1] : '' }))
    : []
  return { snap, headers }
}

// --- internals -------------------------------------------------------------

// Pushes the bytes through a (De)CompressionStream and collects the transformed output. Response is a
// convenient sink that concatenates the readable side into one ArrayBuffer. The writer-side promise is
// swallowed: on malformed input (a bad "D" blob) both sides reject, and the readable's rejection below
// already surfaces the error to the caller — an unawaited writer rejection would only add an
// unhandled-rejection console warning on top.
async function streamThrough(bytes, stream) {
  const writer = stream.writable.getWriter()
  writer.write(bytes).then(() => writer.close()).catch(() => {})
  const buf = await new Response(stream.readable).arrayBuffer()
  return new Uint8Array(buf)
}

// Uint8Array → base64url (URL-safe alphabet, no padding), so the blob survives in a fragment without
// percent-encoding. Chunked so a large body doesn't overflow the argument list of String.fromCharCode.
function bytesToBase64url(bytes) {
  let bin = ''
  const chunk = 0x8000
  for (let i = 0; i < bytes.length; i += chunk) bin += String.fromCharCode.apply(null, bytes.subarray(i, i + chunk))
  return btoa(bin).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '')
}

// base64url → Uint8Array. Restores the standard alphabet and the padding atob expects.
function base64urlToBytes(str) {
  let b64 = str.replace(/-/g, '+').replace(/_/g, '/')
  const pad = b64.length % 4
  if (pad) b64 += '='.repeat(4 - pad)
  const bin = atob(b64)
  const bytes = new Uint8Array(bin.length)
  for (let i = 0; i < bin.length; i++) bytes[i] = bin.charCodeAt(i)
  return bytes
}
