// Lightweight, dependency-free JSON syntax highlighter for read-only display (the Schema → Examples
// cards). It HTML-escapes the input first, then wraps tokens in <span>s using the SAME token classes
// the Raw JSON editor colours (--cm-string/number/bool/null), so highlighting is consistent without
// paying for a CodeMirror instance per card. Object keys inherit the text colour, matching the editor.
//
// Input must be a (pretty-printed) JSON string. Output is safe to use with v-html: every character of
// the input is escaped before any markup is added, so example values can't inject HTML.
export function highlightJson(jsonStr) {
  const esc = String(jsonStr).replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;')
  return esc.replace(
    /("(?:\\.|[^"\\])*")(\s*:)?|\b(true|false)\b|\b(null)\b|(-?\d+(?:\.\d+)?(?:[eE][+-]?\d+)?)/g,
    (m, str, colon, bool, nul, num) => {
      if (str !== undefined) {
        return colon ? `<span class="tok-key">${str}</span>${colon}` : `<span class="tok-str">${str}</span>`
      }
      if (bool !== undefined) return `<span class="tok-bool">${m}</span>`
      if (nul !== undefined) return `<span class="tok-null">${m}</span>`
      if (num !== undefined) return `<span class="tok-num">${m}</span>`
      return m
    }
  )
}

// HTML-escapes plain (non-JSON) example text so it can share the same v-html rendering path.
export function escapeHtml(text) {
  return String(text).replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;')
}
