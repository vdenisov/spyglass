import { ref, onBeforeUnmount } from 'vue'

// A transient "✓ done" flag for copy/apply confirmations. flash() turns the flag on and flips it back
// after `ms`, debouncing repeat triggers; the timer is cleared on unmount so a late callback can't
// write to a torn-down component. Returns { flag, flash }.
export function useFlash(ms = 1500) {
  const flag = ref(false)
  let timer = null
  const flash = () => {
    flag.value = true
    clearTimeout(timer)
    timer = setTimeout(() => { flag.value = false }, ms)
  }
  onBeforeUnmount(() => clearTimeout(timer))
  return { flag, flash }
}
