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
