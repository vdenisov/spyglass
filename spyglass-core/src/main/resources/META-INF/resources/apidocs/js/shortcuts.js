// The canonical list of the explorer's keyboard shortcuts, grouped by where each one applies. This is
// the single source of truth the help overlay (KeyboardHelp.js, opened with "?") renders — a new
// shortcut is documented by adding one line here. Kept as plain data (no registry) so it stays
// trivially greppable and editable in one place; a future command palette (#34) adds its own row here.
//
// Each item's `keys` array is rendered as a row of <kbd> badges. The sentinel key "Mod" renders
// platform-aware — ⌘ on macOS, Ctrl elsewhere (see KeyboardHelp.js). Where a section lists ←/→ or
// Home/End as separate rows, the section heading supplies the context they apply in (the operation
// list, the operation-panel tabs, or the focused resize divider).
export const SHORTCUTS = [
  {
    section: 'Global',
    items: [
      { keys: ['?'], desc: 'Show this keyboard-shortcuts help' },
      { keys: ['/'], desc: 'Focus the operation filter' },
      { keys: ['Mod', 'Enter'], desc: 'Send the current request' }
    ]
  },
  {
    section: 'Operation list',
    items: [
      { keys: ['↓'], desc: 'Move to the next operation' },
      { keys: ['↑'], desc: 'Move to the previous operation (from the top row, back to the filter)' },
      { keys: ['Home'], desc: 'Jump to the first operation' },
      { keys: ['End'], desc: 'Jump to the last operation' },
      { keys: ['Enter'], desc: 'Open the focused operation' },
      { keys: ['Esc'], desc: 'Clear the filter, or from the list return focus to it' }
    ]
  },
  {
    section: 'Operation panel',
    items: [
      { keys: ['←'], desc: 'Previous tab (Try it out / Schema, Form / Raw JSON)' },
      { keys: ['→'], desc: 'Next tab' }
    ]
  },
  {
    section: 'Sidebar resize',
    items: [
      { keys: ['←'], desc: 'Narrow the sidebar (hold Shift for larger steps)' },
      { keys: ['→'], desc: 'Widen the sidebar (hold Shift for larger steps)' },
      { keys: ['Home'], desc: 'Sidebar to its minimum width' },
      { keys: ['End'], desc: 'Sidebar to its maximum width' },
      { keys: ['f'], desc: 'Fit the sidebar to the widest operation (or double-click the divider)' }
    ]
  }
]
