import { ref, computed, watch, onMounted, onBeforeUnmount } from 'vue'
import { VERSION } from '../version.js'

// Lists operations grouped by tag with a live filter; emits `select` when one is chosen.
// Keyboard model (consistent whether you arrive by typing or by clicking):
//   "/"                focuses the filter from anywhere (except while typing/in the editor)
//   ↓ (in the filter)  moves focus into the list at the active op
//   ↑/↓/Home/End       move the focus/highlight between ops; selection follows focus (debounced, so
//                      rapid arrowing only opens the op you settle on)
//   Enter              opens the focused op immediately
//   Escape             from the list returns to the filter; from the filter clears it, then blurs
// Clicking an op opens it immediately and makes it the focused (arrow-navigable) row.
export default {
  name: 'Sidebar',
  props: {
    operations: { type: Array, required: true },
    selectedId: { type: String, default: '' },
    title: { type: String, default: 'API' }
  },
  emits: ['select'],
  setup(props, { emit }) {
    const filter = ref('')
    const filterInput = ref(null)
    const rootEl = ref(null)
    const groups = computed(() => {
      const f = filter.value.trim().toLowerCase()
      const map = new Map()
      for (const op of props.operations) {
        if (f && !(op.path.toLowerCase().includes(f) || op.method.toLowerCase().includes(f) || op.summary.toLowerCase().includes(f)
          || (op.operationId && op.operationId.toLowerCase().includes(f)))) continue
        for (const tag of op.tags) {
          if (!map.has(tag)) map.set(tag, [])
          map.get(tag).push(op)
        }
      }
      return [...map.entries()].sort((a, b) => a[0].localeCompare(b[0])).map(([tag, ops]) => ({ tag, ops }))
    })

    // Flattened filtered order (matches DOM order across tag groups) for ↑/↓ navigation. activeIndex
    // is the focus/highlight cursor; the op-link elements carry a roving tabindex so Tab lands on it.
    const flatOps = computed(() => groups.value.flatMap(g => g.ops))
    const activeIndex = ref(0)
    const filterFocused = ref(false)
    const listFocused = ref(false)
    const isActiveIndex = (op) => flatOps.value[activeIndex.value] && flatOps.value[activeIndex.value].id === op.id
    // The sidebar owns keyboard navigation when either the filter or a list row holds focus. The
    // selection bar's colour keys off this (accent when keyboard-active, neutral grey otherwise).
    const kbActive = computed(() => filterFocused.value || listFocused.value)
    // The single highlighted row: the cursor (activeIndex) while the sidebar is keyboard-active, else
    // the committed selection. Tying it to the cursor — not the debounced panel commit — keeps the bar
    // moving with the keyboard, with no lag and no stale leftover on the previously-opened row.
    const isHighlighted = (op) => isActiveIndex(op) && (kbActive.value || op.id === props.selectedId)
    // Accessible name for an op row: method + path + the summary and deprecated state. The visual
    // summary tooltip is hover-only (quiet during arrow nav), so assistive tech gets the same hint
    // from here when the row is focused.
    const opLabel = (op) => op.method + ' ' + op.path
      + (op.summary ? ' — ' + op.summary : '')
      + (op.deprecated ? ' (deprecated)' : '')
    // Re-anchor the cursor to the first match whenever the result set changes.
    watch(flatOps, (list) => { activeIndex.value = list.length ? 0 : -1 })
    // Track an external selection (deep link / browser back-forward): move the cursor onto it — unless
    // the user is actively arrowing (an op-link holds focus), where the cursor leads and the debounced
    // commit catches up, so syncing here would yank it back.
    watch(() => props.selectedId, (id) => {
      if (!id || (rootEl.value && rootEl.value.querySelector('.op-link:focus'))) return
      const i = flatOps.value.findIndex(o => o.id === id)
      if (i >= 0) activeIndex.value = i
    })

    // Selection follows focus, but the panel commit (which mounts the OperationPanel) is debounced so
    // holding ↓ through a long list doesn't mount a panel per keystroke — only the settled op opens.
    // Click and Enter commit immediately.
    let commitTimer = null
    const commitDebounced = (op) => {
      if (commitTimer) clearTimeout(commitTimer)
      commitTimer = setTimeout(() => { commitTimer = null; if (op) emit('select', op) }, 120)
    }
    const commitNow = (op) => {
      if (commitTimer) { clearTimeout(commitTimer); commitTimer = null }
      if (op) emit('select', op)
    }
    onBeforeUnmount(() => { if (commitTimer) clearTimeout(commitTimer) })

    const opEls = () => (rootEl.value ? Array.from(rootEl.value.querySelectorAll('.op-link')) : [])
    const focusOp = (i) => {
      const el = opEls()[i]
      if (el) { el.focus(); el.scrollIntoView({ block: 'nearest' }) }
    }
    const focusFilter = () => { if (filterInput.value) filterInput.value.focus() }

    // Move the cursor to a list index: highlight + focus instantly, open it after the debounce.
    const navTo = (i) => {
      const n = flatOps.value.length
      if (!n) return
      const idx = Math.max(0, Math.min(i, n - 1))
      activeIndex.value = idx
      focusOp(idx)
      commitDebounced(flatOps.value[idx])
    }

    const onOpKeydown = (e) => {
      const i = activeIndex.value
      if (e.key === 'ArrowDown') { e.preventDefault(); navTo(i + 1) }
      else if (e.key === 'ArrowUp') { e.preventDefault(); if (i <= 0) focusFilter(); else navTo(i - 1) }
      else if (e.key === 'Home') { e.preventDefault(); navTo(0) }
      else if (e.key === 'End') { e.preventDefault(); navTo(flatOps.value.length - 1) }
      else if (e.key === 'Escape') { e.preventDefault(); focusFilter() }
    }

    // Clicking opens immediately and makes the row the arrow-nav cursor (the button takes focus too).
    const choose = (op) => {
      const i = flatOps.value.findIndex(o => o.id === op.id)
      if (i >= 0) activeIndex.value = i
      commitNow(op)
    }

    // Clicking sidebar chrome that isn't the filter, a row, or a link (the list's empty space, the
    // brand, the footer) would otherwise drop focus to <body> and stop ↑/↓ working. Keep the list
    // navigable by moving focus to the active row instead — but never hijack a real link (the footer's
    // GitHub link must navigate/focus normally).
    const onSidebarMousedown = (e) => {
      if (e.target.closest('.op-link, .filter-wrap, a[href]') || !flatOps.value.length) return
      e.preventDefault()
      focusOp(activeIndex.value >= 0 ? activeIndex.value : 0)
    }

    // The filter's mouse affordance for the keyboard Escape-to-clear; keeps focus on the input.
    const clearFilter = () => { filter.value = ''; if (filterInput.value) filterInput.value.focus() }

    const onFilterKeydown = (e) => {
      if (e.key === 'ArrowDown' || e.key === 'ArrowUp') {
        e.preventDefault()
        if (!flatOps.value.length) return
        const i = activeIndex.value < 0 ? 0 : activeIndex.value
        navTo(i)
      } else if (e.key === 'Escape') {
        if (filter.value) filter.value = ''
        else if (filterInput.value) filterInput.value.blur()
      }
    }

    // Track where focus is inside the sidebar (the filter input vs. an op-link in the list), so the
    // highlight can follow the cursor. focusout's relatedTarget is the element about to receive focus,
    // so arrowing op→op keeps listFocused true while op→filter / op→panel flips it.
    const focusStateFrom = (el) => {
      const cl = el && el.classList
      listFocused.value = !!(cl && cl.contains('op-link'))
      filterFocused.value = !!(cl && cl.contains('filter'))
    }
    const onFocusin = (e) => focusStateFrom(e.target)
    const onFocusout = (e) => focusStateFrom(e.relatedTarget)

    // "/" jumps to the filter — but not while typing in a field or the CodeMirror editor.
    const onGlobalKeydown = (e) => {
      if (e.key !== '/' || e.ctrlKey || e.metaKey || e.altKey) return
      const t = e.target
      const tag = (t && t.tagName || '').toUpperCase()
      if (tag === 'INPUT' || tag === 'TEXTAREA' || tag === 'SELECT' || (t && t.isContentEditable)) return
      e.preventDefault()
      if (filterInput.value) { filterInput.value.focus(); filterInput.value.select() }
    }
    onMounted(() => document.addEventListener('keydown', onGlobalKeydown))
    onBeforeUnmount(() => document.removeEventListener('keydown', onGlobalKeydown))

    // Spyglass's own version, build-injected (version.js). In an unfiltered checkout the literal token
    // survives — hide the version line rather than render it raw.
    const version = computed(() => VERSION.startsWith('@') ? '' : VERSION)

    return {
      filter, filterInput, rootEl, groups, kbActive, isActiveIndex, isHighlighted, opLabel,
      onFilterKeydown, onOpKeydown, choose, clearFilter, onFocusin, onFocusout, onSidebarMousedown, version
    }
  },
  template: `
    <aside class="sidebar" ref="rootEl" @focusin="onFocusin" @focusout="onFocusout" @mousedown="onSidebarMousedown">
      <h1 class="brand">{{ title }}</h1>
      <div class="filter-wrap">
        <input class="filter" ref="filterInput" v-model="filter" @keydown="onFilterKeydown"
          aria-label="Filter operations" placeholder="Filter operations…  ( / )" />
        <button v-if="filter" type="button" class="filter-clear" @mousedown.prevent @click="clearFilter"
          aria-label="clear filter" v-tip="'Clear filter (Esc)'">✕</button>
      </div>
      <div class="op-list" role="listbox" aria-label="Operations" :class="{ 'kb-active': kbActive }">
        <div v-for="grp in groups" :key="grp.tag" class="tag-group" role="group" :aria-label="grp.tag">
          <div class="tag-name">{{ grp.tag }}</div>
          <button v-for="op in grp.ops" :key="op.id" type="button" role="option"
            :aria-selected="isHighlighted(op)" :tabindex="isActiveIndex(op) ? 0 : -1"
            class="op-link" :class="['m-' + op.method.toLowerCase(), { active: isHighlighted(op), deprecated: op.deprecated }]"
            :aria-label="opLabel(op)"
            @click="choose(op)" @keydown="onOpKeydown" v-tip.hover="op.deprecated ? (op.summary + ' (deprecated)') : op.summary">
            <span class="op-method">{{ op.method }}</span>
            <span class="op-path">{{ op.path }}</span>
            <span v-if="op.deprecated" class="dep-tag sidebar-dep">depr</span>
          </button>
        </div>
        <div v-if="!groups.length" class="hint">No operations match.</div>
      </div>
      <div class="sidebar-foot">
        <span class="foot-brand"><span class="foot-name">Spyglass</span> · OpenAPI Explorer</span>
        <span class="foot-meta">
          <span v-if="version" class="foot-version">v{{ version }}</span>
          <a class="foot-link" href="https://github.com/vdenisov/spyglass" target="_blank" rel="noopener"
             v-tip="'github.com/vdenisov/spyglass'">GitHub ↗</a>
        </span>
      </div>
    </aside>
  `
}
