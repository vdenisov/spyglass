// Copies text to the clipboard, returning whether it succeeded. The clipboard API may be blocked
// (insecure context, permissions), so callers should tolerate a false result.
export async function copyText(text) {
  try {
    await navigator.clipboard.writeText(text)
    return true
  } catch (e) {
    return false
  }
}
