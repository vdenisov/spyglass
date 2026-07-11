// Classifies an HTTP status code for styling: 2xx success, 3xx redirect (neutral — not an error),
// everything else (4xx/5xx, or a non-numeric placeholder like "—") an error. Shared by the Schema
// tab's response list and the live response view.
export function statusKind(status) {
  const code = parseInt(status, 10)
  if (code >= 200 && code < 300) return 'ok'
  if (code >= 300 && code < 400) return 'redirect'
  return 'err'
}

// Canonical reason phrases for the standard IANA status codes. Used as a fallback when the response
// carries no reason phrase of its own — most notably over HTTP/2, which dropped the reason phrase
// from the protocol, so Response.statusText is always empty there.
const REASON_PHRASES = {
  100: 'Continue', 101: 'Switching Protocols', 102: 'Processing', 103: 'Early Hints',
  200: 'OK', 201: 'Created', 202: 'Accepted', 203: 'Non-Authoritative Information',
  204: 'No Content', 205: 'Reset Content', 206: 'Partial Content', 207: 'Multi-Status',
  208: 'Already Reported', 226: 'IM Used',
  300: 'Multiple Choices', 301: 'Moved Permanently', 302: 'Found', 303: 'See Other',
  304: 'Not Modified', 305: 'Use Proxy', 307: 'Temporary Redirect', 308: 'Permanent Redirect',
  400: 'Bad Request', 401: 'Unauthorized', 402: 'Payment Required', 403: 'Forbidden',
  404: 'Not Found', 405: 'Method Not Allowed', 406: 'Not Acceptable',
  407: 'Proxy Authentication Required', 408: 'Request Timeout', 409: 'Conflict', 410: 'Gone',
  411: 'Length Required', 412: 'Precondition Failed', 413: 'Content Too Large',
  414: 'URI Too Long', 415: 'Unsupported Media Type', 416: 'Range Not Satisfiable',
  417: 'Expectation Failed', 418: "I'm a Teapot", 421: 'Misdirected Request',
  422: 'Unprocessable Content', 423: 'Locked', 424: 'Failed Dependency', 425: 'Too Early',
  426: 'Upgrade Required', 428: 'Precondition Required', 429: 'Too Many Requests',
  431: 'Request Header Fields Too Large', 451: 'Unavailable For Legal Reasons',
  500: 'Internal Server Error', 501: 'Not Implemented', 502: 'Bad Gateway',
  503: 'Service Unavailable', 504: 'Gateway Timeout', 505: 'HTTP Version Not Supported',
  506: 'Variant Also Negotiates', 507: 'Insufficient Storage', 508: 'Loop Detected',
  510: 'Not Extended', 511: 'Network Authentication Required'
}

// Canonical reason phrase for a status code, or '' for an unknown / non-numeric one (e.g. the "—"
// placeholder used for cancelled / network-error responses).
export function reasonPhrase(status) {
  return REASON_PHRASES[parseInt(status, 10)] || ''
}

// The status line's display text: "409 Conflict". A server-supplied reason phrase is kept verbatim;
// when absent, the canonical phrase for the code fills in; an unknown code with no phrase shows the
// bare number (no trailing space). Shared by the live response view and the Request Log.
export function statusLabel(status, statusText) {
  const phrase = (statusText || '').trim() || reasonPhrase(status)
  return phrase ? `${status} ${phrase}` : `${status}`
}

// Compact, human-readable byte count (e.g. "1.2 KB"). Shared by the file inputs and the response view.
export function formatBytes(n) {
  if (n == null) return ''
  if (n < 1024) return n + ' B'
  const units = ['KB', 'MB', 'GB']
  let v = n / 1024
  let i = 0
  while (v >= 1024 && i < units.length - 1) { v /= 1024; i++ }
  return v.toFixed(1) + ' ' + units[i]
}

// Extracts the filename from a Content-Disposition header (handling RFC 5987 filename*= and quotes),
// or '' when none is present. Shared by the response download control and the Request Log.
export function filenameFromDisposition(cd) {
  if (!cd) return ''
  const star = /filename\*\s*=\s*(?:[^']*'[^']*')?([^;]+)/i.exec(cd)
  if (star) { try { return decodeURIComponent(star[1].trim().replace(/^"|"$/g, '')) } catch (e) { /* fall through */ } }
  const plain = /filename\s*=\s*("([^"]*)"|[^;]+)/i.exec(cd)
  if (plain) return (plain[2] != null ? plain[2] : plain[1]).trim()
  return ''
}
