import { ref, watch, nextTick, onBeforeUnmount } from 'vue'

// Per-instance id seed for the dialog's aria-labelledby wiring. Mirrors ComboBox.js's uid pattern.
let modalUid = 0

// Elements that take part in the Tab order — the focus trap keeps Tab/Shift+Tab cycling among these
// while the dialog is open.
const FOCUSABLE = 'a[href], button:not([disabled]), input:not([disabled]), select:not([disabled]), textarea:not([disabled]), [tabindex]:not([tabindex="-1"])'

// A generic, reusable modal dialog: a full-viewport backdrop with a centered panel. Presentational and
// feature-agnostic — the caller supplies the body through the default slot and owns both the open
// state (`show`) and what closing means (`@close`). This is the app's first dialog; the keyboard
// command palette (#34) is expected to reuse it.
//
// Behaviour it owns so callers don't reimplement it: role="dialog" + aria-modal, a focus trap,
// Escape-to-close, focus restore to whatever was focused before it opened, a background scroll lock,
// and backdrop-click close. No <Teleport> (the app uses none) — position:fixed makes the panel's
// render location in the DOM irrelevant, so it renders inline in the parent's template.
export default {
  name: 'Modal',
  props: {
    show: { type: Boolean, default: false },
    title: { type: String, default: '' }
  },
  emits: ['close'],
  setup(props, { emit }) {
    const panel = ref(null)
    const titleId = 'modal-' + (++modalUid) + '-title'
    // The element focused before the dialog opened, restored on close so focus never falls to <body>.
    let lastFocused = null
    // The body's overflow before the scroll lock, restored when the dialog closes.
    let savedOverflow = ''

    const focusables = () => panel.value ? Array.from(panel.value.querySelectorAll(FOCUSABLE)) : []

    // Escape closes; Tab/Shift+Tab wrap within the panel. Capture phase so the trap runs before any
    // background keydown handler (e.g. the sidebar's "/" or the panel's Ctrl/Cmd+Enter).
    const onKeydown = (e) => {
      if (e.key === 'Escape') { e.preventDefault(); emit('close'); return }
      if (e.key !== 'Tab') return
      const els = focusables()
      if (!els.length) { e.preventDefault(); return }
      const first = els[0]
      const last = els[els.length - 1]
      const active = document.activeElement
      const outside = !panel.value.contains(active)
      if (e.shiftKey) {
        if (outside || active === first) { e.preventDefault(); last.focus() }
      } else {
        if (outside || active === last) { e.preventDefault(); first.focus() }
      }
    }

    // Close only on a press that both starts and ends on the backdrop itself (not on a drag that began
    // inside the panel and released outside).
    const onBackdrop = (e) => { if (e.target === e.currentTarget) emit('close') }

    const teardown = () => {
      document.removeEventListener('keydown', onKeydown, true)
      document.body.style.overflow = savedOverflow
      if (lastFocused && typeof lastFocused.focus === 'function' && document.contains(lastFocused)) lastFocused.focus()
      lastFocused = null
    }

    watch(() => props.show, (show) => {
      if (show) {
        lastFocused = document.activeElement
        document.addEventListener('keydown', onKeydown, true)
        savedOverflow = document.body.style.overflow
        document.body.style.overflow = 'hidden'
        // Focus the dialog container (tabindex=-1) so assistive tech announces it; the first Tab then
        // moves to the close button.
        nextTick(() => { if (panel.value) panel.value.focus() })
      } else {
        teardown()
      }
    })
    onBeforeUnmount(() => { if (props.show) teardown() })

    return { panel, titleId, onBackdrop }
  },
  template: `
    <div v-if="show" class="modal-backdrop" @mousedown="onBackdrop">
      <div class="modal" ref="panel" role="dialog" aria-modal="true" :aria-labelledby="titleId" tabindex="-1">
        <div class="modal-header">
          <h2 class="modal-title" :id="titleId">{{ title }}</h2>
          <button type="button" class="modal-close" aria-label="Close" @click="$emit('close')"><span aria-hidden="true">✕</span></button>
        </div>
        <div class="modal-body">
          <slot />
        </div>
      </div>
    </div>
  `
}
