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
