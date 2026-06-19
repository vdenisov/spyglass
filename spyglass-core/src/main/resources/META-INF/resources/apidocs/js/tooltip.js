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

let tipEl = null
let activeTarget = null
let showTimer = null
let listenersBound = false

function ensureTip() {
  if (tipEl) return tipEl
  tipEl = document.createElement('div')
  tipEl.className = 'app-tooltip'
  tipEl.setAttribute('role', 'tooltip')
  document.body.appendChild(tipEl)
  return tipEl
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
  const r = el.getBoundingClientRect()
  const tw = tip.offsetWidth
  const th = tip.offsetHeight

  let top = r.top - th - TARGET_GAP
  if (top < VIEWPORT_MARGIN) top = r.bottom + TARGET_GAP // flip below when there's no room above

  let left = r.left + r.width / 2 - tw / 2
  left = Math.max(VIEWPORT_MARGIN, Math.min(left, window.innerWidth - tw - VIEWPORT_MARGIN))

  tip.style.top = Math.round(top) + 'px'
  tip.style.left = Math.round(left) + 'px'
  tip.classList.add('visible')
}

function show(el, text) {
  bindGlobalListeners()
  activeTarget = el
  position(el, text)
}

function hide() {
  clearTimeout(showTimer)
  showTimer = null
  activeTarget = null
  if (tipEl) tipEl.classList.remove('visible')
}

function attach(el) {
  const enter = () => {
    if (!el.__tipText) return
    clearTimeout(showTimer)
    showTimer = setTimeout(() => show(el, el.__tipText), SHOW_DELAY_MS)
  }
  const leave = () => {
    if (activeTarget === el || showTimer) hide()
  }
  el.__tipEnter = enter
  el.__tipLeave = leave
  el.addEventListener('mouseenter', enter)
  el.addEventListener('mouseleave', leave)
  el.addEventListener('focusin', enter)
  el.addEventListener('focusout', leave)
}

function detach(el) {
  el.removeEventListener('mouseenter', el.__tipEnter)
  el.removeEventListener('mouseleave', el.__tipLeave)
  el.removeEventListener('focusin', el.__tipEnter)
  el.removeEventListener('focusout', el.__tipLeave)
}

export default {
  mounted(el, binding) {
    el.__tipText = binding.value || ''
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
