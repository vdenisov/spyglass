// Build-time replacement for codemirror-json-schema's hover/diagnostic markdown renderer
// (its `dist/utils/markdown.js`), which statically pulls in shiki + markdown-it just to render
// syntax-highlighted markdown tooltips. We reuse `marked` — already vendored and loaded by the
// explorer (see js/markdown.js) — so tooltips still render real markdown, but the shiki engine
// and ~30 transitive packages drop out of the bundle. `marked` is kept external at build time
// (see build.mjs), so this resolves to the same shared marked instance via the importmap.
//
// Same signature as the original `renderMarkdown(markdown, inline = true)`; returns HTML that the
// callers insert as a tooltip `inner` / `dom.innerHTML`. Content is team-authored spec text and
// validator messages (same trust model as the app's existing marked usage).
import { parse, parseInline } from 'marked'

const OPTS = { gfm: true, breaks: true }

export function renderMarkdown(markdown, inline = true) {
  const text = markdown == null ? '' : String(markdown)
  if (!text) return ''
  return inline ? parseInline(text, OPTS) : parse(text, OPTS)
}
