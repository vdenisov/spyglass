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
  '.cm-activeLine': { backgroundColor: 'var(--accent-weak)' },
  '.cm-activeLineGutter': { backgroundColor: 'var(--accent-weak)' },
  '.cm-selectionBackground, &.cm-focused .cm-selectionBackground': { backgroundColor: 'var(--accent-weak)' }
})
