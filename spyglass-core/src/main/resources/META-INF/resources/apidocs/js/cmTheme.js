// Shared, theme-aware CodeMirror styling for the JSON editor and the read-only response viewer.
// Colors are CSS variables (see styles.css), so the editor follows light/dark without rebuilding.

import { EditorView, HighlightStyle, syntaxHighlighting, tags as t } from 'codemirror-bundle'

// Value-type highlight. basicSetup's default highlight style is registered as a fallback only, so
// this one wins. The light variable values reproduce CodeMirror's own defaults, so light mode is
// unchanged; only the four value types are overridden. Keys (propertyName) and punctuation inherit
// the editor text color (--text) and stay readable in both themes.
export const highlight = syntaxHighlighting(HighlightStyle.define([
  { tag: t.string, color: 'var(--cm-string)' },
  { tag: t.number, color: 'var(--cm-number)' },
  { tag: t.bool, color: 'var(--cm-bool)' },
  { tag: t.null, color: 'var(--cm-null)' }
]))

// Surface theme: the editor chrome (background, gutter, caret, active line, selection) keyed to
// CSS variables so it flips with the app theme.
export const surfaceTheme = EditorView.theme({
  '&': { border: '1px solid var(--border)', borderRadius: '6px', fontSize: '12px', backgroundColor: 'var(--bg)', color: 'var(--text)' },
  '&.cm-focused': { outline: 'none', borderColor: 'var(--accent)' },
  '.cm-content': { fontFamily: 'var(--mono)', caretColor: 'var(--text)' },
  '.cm-cursor, .cm-dropCursor': { borderLeftColor: 'var(--text)' },
  '.cm-scroller': { maxHeight: '320px' },
  '.cm-gutters': { backgroundColor: 'var(--bg-alt)', color: 'var(--muted)', border: 'none' },
  // Active-line highlight: a low-alpha wash on the line body (--cm-active-line) and a slightly stronger,
  // same-hue fill on the gutter (--cm-active-gutter), so the two read as one band. The body sits in the
  // content layer, above the selection layer, so its alpha is kept low enough that a selection on the
  // cursor's line shows through. Applies to both the read-only viewer and the editable JSON editor.
  '.cm-activeLine': { backgroundColor: 'var(--cm-active-line)' },
  '.cm-activeLineGutter': { backgroundColor: 'var(--cm-active-gutter)' },
  // Both the focused and unfocused selection use --cm-selection. The !important is required because
  // CodeMirror's built-in FOCUSED selection rule (a lavender default, #d7d4f0) uses a longer, more
  // specific selector than a plain theme override — so without it the editable editor shows CM's
  // lavender when focused (unreadable on the dark theme), while the read-only viewer, which never gets
  // focus, looked correct.
  '.cm-selectionBackground, &.cm-focused .cm-selectionBackground': { backgroundColor: 'var(--cm-selection) !important' }
})
