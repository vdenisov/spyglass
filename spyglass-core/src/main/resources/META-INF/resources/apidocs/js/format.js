// Classifies an HTTP status code for styling: 2xx success, 3xx redirect (neutral — not an error),
// everything else (4xx/5xx, or a non-numeric placeholder like "—") an error. Shared by the Schema
// tab's response list and the live response view.
export function statusKind(status) {
  const code = parseInt(status, 10)
  if (code >= 200 && code < 300) return 'ok'
  if (code >= 300 && code < 400) return 'redirect'
  return 'err'
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
