import { parse, parseInline, setOptions } from 'marked'

// Render spec descriptions (team-authored, same-origin) as markdown via marked.
// Note: setOptions mutates marked's process-global config — fine here as the explorer is marked's only
// consumer, but any future vendored user of marked would inherit gfm/breaks.
setOptions({ gfm: true, breaks: true })

// Block-level (paragraphs, lists) — use in a block container.
export function mdBlock(text) { return text ? parse(text) : '' }

// Inline only (no wrapping <p>) — use inside spans / single-line rows.
export function mdInline(text) { return text ? parseInline(text) : '' }
