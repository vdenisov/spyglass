// A themed, smart-positioned hover/focus tooltip, used as a directive: v-tip="'some text'".
//
// One shared tooltip element is appended to <body> (position: fixed) so it never clips inside a
// scrolling panel — the weakness of a CSS-only ::after tooltip. It is theme-aware (built from the
// app's CSS variables), flips above→below when there isn't room, clamps horizontally to the
// viewport, and shows on hover or keyboard focus. Trivial affordance labels keep the native title;
// this is for the explanatory hints where the dark, unstyleable native tooltip looked out of place.

const SHOW_DELAY_MS = 250
const VIEWPORT_MARGIN = 8
const TARGET_GAP = 6
const TOOLTIP_ID = 'spyglass-tooltip' // links the shown target to the tooltip text via aria-describedby

let tipEl = null
let activeTarget = null
let showTimer = null
let listenersBound = false
// Input-modality tracking so the focus trigger fires only for keyboard focus. A pointer (mouse click
// or touch tap) that moves focus should not raise the tooltip: on desktop hover already covers it, and
// on touch there is no mouseleave and the tapped control keeps focus, so a focus tooltip would pop on
// tap and linger. `focusFromKeyboard` flips false on any pointerdown and true on any keydown (Tab, etc).
let modalityBound = false
let focusFromKeyboard = false
// Last known pointer position, for `.cursor` tooltips that anchor to the mouse instead of the target
// rect (used by the full-height resize divider, whose rect spans the whole page). pointerActive is
// false when the tooltip was triggered by keyboard focus, so it falls back to rect positioning.
let pointerX = 0
let pointerY = 0
let pointerActive = false

function ensureTip() {
  if (tipEl) return tipEl
  tipEl = document.createElement('div')
  tipEl.className = 'app-tooltip'
  tipEl.id = TOOLTIP_ID
  tipEl.setAttribute('role', 'tooltip')
  document.body.appendChild(tipEl)
  return tipEl
}

// Track what caused focus, once, in the capture phase so the flag is settled before focusin runs.
function bindModalityListeners() {
  if (modalityBound) return
  modalityBound = true
  document.addEventListener('pointerdown', () => { focusFromKeyboard = false }, true)
  document.addEventListener('keydown', () => { focusFromKeyboard = true }, true)
}

function bindGlobalListeners() {
  if (listenersBound) return
  listenersBound = true
  // The tooltip is viewport-fixed, so it would drift on scroll — dismiss instead of chasing.
  window.addEventListener('scroll', hide, true)
  window.addEventListener('resize', hide)
  document.addEventListener('keydown', (e) => { if (e.key === 'Escape') hide() })
}

function position(el, text) {
  const tip = ensureTip()
  tip.textContent = text
  // Measure with layout present (opacity 0, not display:none) before revealing.
  const tw = tip.offsetWidth
  const th = tip.offsetHeight

  let top, left
  if (el.__tipCursor && pointerActive) {
    // Anchor to the cursor: a tall/thin target (the resize divider) has no meaningful rect anchor,
    // so show the hint just below-right of the pointer.
    top = pointerY + 18
    left = pointerX + 12
  } else {
    const r = el.getBoundingClientRect()
    top = r.top - th - TARGET_GAP
    if (top < VIEWPORT_MARGIN) top = r.bottom + TARGET_GAP // flip below when there's no room above
    left = r.left + r.width / 2 - tw / 2
  }
  // Clamp into the viewport so a tall target (or a cursor near an edge) still shows a fully visible
  // tooltip rather than one pinned off-screen.
  top = Math.max(VIEWPORT_MARGIN, Math.min(top, window.innerHeight - th - VIEWPORT_MARGIN))
  left = Math.max(VIEWPORT_MARGIN, Math.min(left, window.innerWidth - tw - VIEWPORT_MARGIN))

  tip.style.top = Math.round(top) + 'px'
  tip.style.left = Math.round(left) + 'px'
  tip.classList.add('visible')
}

function show(el, text) {
  bindGlobalListeners()
  if (activeTarget && activeTarget !== el) activeTarget.removeAttribute('aria-describedby')
  activeTarget = el
  position(el, text)
  // Point the target at the (now-populated) shared tooltip so a screen reader announces the hint.
  el.setAttribute('aria-describedby', TOOLTIP_ID)
}

function hide() {
  clearTimeout(showTimer)
  showTimer = null
  if (activeTarget) activeTarget.removeAttribute('aria-describedby')
  activeTarget = null
  if (tipEl) tipEl.classList.remove('visible')
}

function attach(el) {
  const enter = (e) => {
    if (!el.__tipText) return
    // Suppress on touch. A tap fires a synthetic pointerenter (pointerType 'touch') and keeps focus with
    // no leave, so both the hover and focus tooltips would otherwise linger until the next tap elsewhere.
    // Genuine mouse/pen hover and keyboard focus are unaffected: hover shows on a non-touch pointerenter,
    // and the focus tooltip shows only when focus arrived from the keyboard.
    if (e && e.type === 'pointerenter' && e.pointerType === 'touch') return
    if (e && e.type === 'focusin' && !focusFromKeyboard) return
    // Track the pointer for `.cursor` tooltips; a focus activation (no clientX) falls back to rect.
    if (el.__tipCursor) {
      if (e && typeof e.clientX === 'number') { pointerX = e.clientX; pointerY = e.clientY; pointerActive = true }
      else pointerActive = false
    }
    clearTimeout(showTimer)
    showTimer = setTimeout(() => show(el, el.__tipText), SHOW_DELAY_MS)
  }
  const leave = () => {
    if (activeTarget === el || showTimer) hide()
  }
  // For `.cursor` tooltips, follow the pointer while the hint is shown.
  const move = (e) => {
    pointerX = e.clientX; pointerY = e.clientY; pointerActive = true
    if (activeTarget === el) position(el, el.__tipText)
  }
  bindModalityListeners()
  el.__tipEnter = enter
  el.__tipLeave = leave
  el.__tipMove = move
  // Pointer Events (not mouse*) so the enter handler can read pointerType and skip touch — the whole
  // point of the touch suppression above.
  el.addEventListener('pointerenter', enter)
  el.addEventListener('pointerleave', leave)
  if (el.__tipCursor) el.addEventListener('pointermove', move)
  // `.hover` opts out of the focus trigger (e.g. the sidebar op rows, where it's noisy during arrow
  // navigation and the opened panel already shows the summary). Those elements carry the hint for
  // assistive tech in their own accessible name instead.
  if (!el.__tipHoverOnly) {
    el.addEventListener('focusin', enter)
    el.addEventListener('focusout', leave)
  }
}

function detach(el) {
  el.removeEventListener('pointerenter', el.__tipEnter)
  el.removeEventListener('pointerleave', el.__tipLeave)
  el.removeEventListener('pointermove', el.__tipMove)
  el.removeEventListener('focusin', el.__tipEnter)
  el.removeEventListener('focusout', el.__tipLeave)
}

export default {
  mounted(el, binding) {
    el.__tipText = binding.value || ''
    el.__tipHoverOnly = !!binding.modifiers.hover
    el.__tipCursor = !!binding.modifiers.cursor
    attach(el)
  },
  updated(el, binding) {
    el.__tipText = binding.value || ''
    // Keep a visible tooltip in sync if its target's text changed under the cursor.
    if (activeTarget === el) {
      if (el.__tipText) position(el, el.__tipText)
      else hide()
    }
  },
  beforeUnmount(el) {
    if (activeTarget === el) hide()
    detach(el)
  }
}
