import { ref, computed, watch, onMounted, onUpdated, onBeforeUnmount } from 'vue'
import { VERSION } from '../version.js'
import { registry } from '../extensions.js'

// --- filter ranking ---------------------------------------------------------
// While a filter is active the sidebar abandons tag grouping for a relevance-ranked, sectioned
// list: each operation is bucketed under the single highest-precedence field it matched. path and
// method matches are already visible in the row (highlighted in place); operationId and summary
// matches aren't, so they're explained on a second line, each windowed around the match so the
// found marker stays visible at any column width. Precedence is the MATCH_FIELDS order below.
const MATCH_FIELDS = [
  { key: 'path', label: 'In path', get: op => op.path },
  { key: 'operationId', label: 'In operation ID', get: op => op.operationId },
  { key: 'method', label: 'In method', get: op => op.method },
  { key: 'summary', label: 'In summary', get: op => op.summary }
]
// Snippet windowing (operationId and summary). A matched snippet is shown whole when it fits the
// snippet column; when it's too long a window slides over it so the match stays visible near the
// right with a little trailing margin, dropping the least head necessary (marked with a leading …).
// Both the column width and the text width are *measured* with a canvas using the snippet's real
// font (see remeasure), not estimated from an average glyph width — so it stays correct across
// fonts/zoom and trims smoothly as the divider is dragged. This matters most for operationIds, which
// are typically long fully-qualified names whose matched method fragment sits at the tail.
const SNIPPET_TAIL_PX = 36          // trailing margin kept past the match when windowed, so it isn't
                                    // flush against (or clipped by) the right edge

// The first MATCH_FIELDS entry whose (lowercased) text contains the query, or null. f is assumed
// already lowercased and non-empty.
const classifyMatch = (op, f) => {
  for (const field of MATCH_FIELDS) {
    const text = field.get(op)
    if (text && text.toLowerCase().includes(f)) return field
  }
  return null
}

// Split text at the first case-insensitive occurrence of f into highlight parts [{ t, mark }]. No
// match (or empty f) yields a single plain part, so callers can always v-for the result and only
// the { mark: true } part is wrapped in <mark>.
const highlightParts = (text, f) => {
  const i = f ? text.toLowerCase().indexOf(f) : -1
  if (i < 0) return [{ t: text, mark: false }]
  return [
    { t: text.slice(0, i), mark: false },
    { t: text.slice(i, i + f.length), mark: true },
    { t: text.slice(i + f.length), mark: false }
  ]
}

// Highlight parts for a snippet, windowed to fit width `px` using `ctx` (a canvas context carrying
// the snippet's font). `text` is assumed already normalized. Shows the whole string when it fits (or
// when measurement isn't ready yet, px 0); otherwise drops the least head so the match's right edge
// plus SNIPPET_TAIL_PX still fits, prefixing … . f is assumed lowercased and present in the text.
const windowedParts = (text, f, ctx, px) => {
  const i = text.toLowerCase().indexOf(f)
  if (i < 0) return [{ t: text, mark: false }]
  const end = i + f.length
  const parts = (start) => [
    { t: (start > 0 ? '…' : '') + text.slice(start, i), mark: false },
    { t: text.slice(i, end), mark: true },
    { t: text.slice(end), mark: false }
  ]
  if (!ctx || !px || ctx.measureText(text).width <= px) return parts(0)
  // Too long: binary-search the smallest start (most lead) whose [start, end] still fits the budget,
  // so the match stays visible; start moves one position at a time as px changes — a smooth trim.
  const budgetPx = Math.max(0, px - SNIPPET_TAIL_PX)
  let lo = 0, hi = i
  while (lo < hi) {
    const mid = (lo + hi) >> 1
    if (ctx.measureText(text.slice(mid, end)).width <= budgetPx) hi = mid
    else lo = mid + 1
  }
  return parts(lo)
}

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
    title: { type: String, default: 'API' },
    loading: { type: Boolean, default: false },
    brandingShow: { type: Boolean, default: true },
    // On the narrow layout the sidebar is rendered as an off-canvas drawer; this reveals an in-panel
    // close control (the ✕ in the brand row). Default false keeps the desktop DOM unchanged.
    drawer: { type: Boolean, default: false }
  },
  emits: ['select', 'close'],
  setup(props, { emit }) {
    const filter = ref('')
    const filterInput = ref(null)
    const rootEl = ref(null)
    const q = computed(() => filter.value.trim().toLowerCase())
    const filtering = computed(() => q.value.length > 0)
    // Live measurement for snippet windowing: the available width of a snippet box (reactive, so
    // snippets re-window when it changes) and a canvas context carrying the snippet's real font.
    // Refreshed on mount, on resize, and after renders that change the list (see remeasure / onUpdated).
    const availPx = ref(0)
    let measureCtx = null
    let listPadX = 0        // op-list left+right padding plus the row's left padding, captured once
    const remeasure = () => {
      const list = rootEl.value && rootEl.value.querySelector('.op-list')
      const snip = list && list.querySelector('.op-snippet')
      if (!snip) return
      if (!measureCtx) {
        measureCtx = document.createElement('canvas').getContext('2d')
        const cs = getComputedStyle(snip)
        measureCtx.font = `${cs.fontWeight} ${cs.fontSize} ${cs.fontFamily}`
        const lcs = getComputedStyle(list)
        listPadX = (parseFloat(lcs.paddingLeft) || 0) + (parseFloat(lcs.paddingRight) || 0)
          + (parseFloat(getComputedStyle(snip.parentElement).paddingLeft) || 0)
      }
      // .op-snippet spans the whole row (min-width:100%), so when a long path widens the row past the
      // visible column the snippet's own width tracks that max-content, not what's on screen — which
      // would leave the window barely eliding and the marker scrolled off the right. Cap the budget by
      // the list's visible inner width so the window keeps eliding enough to hold the marker on screen.
      const w = Math.min(snip.clientWidth, list.clientWidth - listPadX)
      if (w > 0) availPx.value = w
    }
    // Unfiltered view: every operation grouped by tag, tags sorted (ops keep spec order within a tag).
    const groups = computed(() => {
      const map = new Map()
      for (const op of props.operations) {
        for (const tag of op.tags) {
          if (!map.has(tag)) map.set(tag, [])
          map.get(tag).push(op)
        }
      }
      return [...map.entries()].sort((a, b) => a[0].localeCompare(b[0])).map(([tag, ops]) => ({ tag, ops }))
    })
    // Filtered view: each matching op ranked into one section by its highest-precedence matched
    // field; sections emitted in MATCH_FIELDS (precedence) order, empties dropped, spec order kept
    // within a section. Each row carries the field key so the template knows what to highlight.
    const sections = computed(() => {
      const f = q.value
      if (!f) return []
      const map = new Map()
      for (const op of props.operations) {
        const field = classifyMatch(op, f)
        if (!field) continue
        if (!map.has(field.key)) map.set(field.key, { key: field.key, label: field.label, rows: [] })
        map.get(field.key).rows.push({ op, field: field.key })
      }
      return MATCH_FIELDS.map(field => map.get(field.key)).filter(Boolean)
    })

    // Flattened active order (matches DOM order: ranked sections while filtering, else tag groups)
    // for ↑/↓ navigation. activeIndex is the focus/highlight cursor; the op-link elements carry a
    // roving tabindex so Tab lands on it.
    const flatOps = computed(() => filtering.value
      ? sections.value.flatMap(s => s.rows.map(r => r.op))
      : groups.value.flatMap(g => g.ops))

    // Highlight parts for a row's method/path — only the field that actually matched is highlighted;
    // the other renders as a single plain part. The explanatory second line (snippetParts) is the
    // windowed operationId or summary, or null for path/method matches (no snippet).
    const methodParts = (row) => highlightParts(row.op.method, row.field === 'method' ? q.value : '')
    const pathParts = (row) => highlightParts(row.op.path, row.field === 'path' ? q.value : '')
    const snippetParts = (row) => {
      if (row.field === 'operationId') return windowedParts(row.op.operationId || '', q.value, measureCtx, availPx.value)
      if (row.field === 'summary') return windowedParts((row.op.summary || '').replace(/\s+/g, ' ').trim(), q.value, measureCtx, availPx.value)
      return null
    }
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

    // "/" jumps to the filter — but not while typing in a field or the CodeMirror editor, and not
    // while a modal dialog is open (focusing the filter behind the backdrop would break its focus trap).
    const onGlobalKeydown = (e) => {
      if (e.key !== '/' || e.ctrlKey || e.metaKey || e.altKey) return
      const t = e.target
      const tag = (t && t.tagName || '').toUpperCase()
      if (tag === 'INPUT' || tag === 'TEXTAREA' || tag === 'SELECT' || (t && t.isContentEditable)) return
      if (document.querySelector('[role="dialog"]')) return
      e.preventDefault()
      if (filterInput.value) { filterInput.value.focus(); filterInput.value.select() }
    }
    // Re-measure the snippet column whenever it can change: on resize (divider drag — which fires no
    // Vue render, hence the ResizeObserver) and after every render (a filter change adds/removes
    // snippets). remeasure only writes availPx when it actually differs, so this doesn't loop.
    let listRO = null
    onMounted(() => {
      document.addEventListener('keydown', onGlobalKeydown)
      const list = rootEl.value && rootEl.value.querySelector('.op-list')
      if (list && 'ResizeObserver' in window) {
        listRO = new ResizeObserver(() => remeasure())
        listRO.observe(list)
      }
      remeasure()
    })
    onUpdated(remeasure)
    onBeforeUnmount(() => {
      document.removeEventListener('keydown', onGlobalKeydown)
      if (listRO) listRO.disconnect()
    })

    // Spyglass's own version, build-injected (version.js). In an unfiltered checkout the literal token
    // survives — hide the version line rather than render it raw.
    const version = computed(() => VERSION.startsWith('@') ? '' : VERSION)

    return {
      filter, filterInput, rootEl, filtering, groups, sections, flatOps, kbActive, isActiveIndex,
      isHighlighted, opLabel, methodParts, pathParts, snippetParts,
      onFilterKeydown, onOpKeydown, choose, clearFilter, onFocusin, onFocusout, onSidebarMousedown, version,
      footerItems: registry.footerItems
    }
  },
  template: `
    <aside class="sidebar" ref="rootEl" @focusin="onFocusin" @focusout="onFocusout" @mousedown="onSidebarMousedown">
      <h1 class="brand">{{ title }}</h1>
      <button v-if="drawer" type="button" class="drawer-close" @click="$emit('close')"
        aria-label="Close operation list" v-tip="'Close (Esc)'"><span aria-hidden="true">✕</span></button>
      <div class="filter-wrap">
        <input class="filter" ref="filterInput" v-model="filter" @keydown="onFilterKeydown"
          aria-label="Filter operations" placeholder="Filter operations…  ( / )" />
        <button v-if="filter" type="button" class="filter-clear" @mousedown.prevent @click="clearFilter"
          aria-label="clear filter" v-tip="'Clear filter (Esc)'">✕</button>
      </div>
      <div class="op-list" role="listbox" aria-label="Operations" :class="{ 'kb-active': kbActive }">
        <template v-if="filtering">
          <div v-for="sec in sections" :key="sec.key" class="match-group" role="group" :aria-label="sec.label">
            <div class="match-name">{{ sec.label }}</div>
            <button v-for="row in sec.rows" :key="row.op.id" type="button" role="option"
              :aria-selected="isHighlighted(row.op)" :tabindex="isActiveIndex(row.op) ? 0 : -1"
              class="op-link" :class="['m-' + row.op.method.toLowerCase(), { active: isHighlighted(row.op), deprecated: row.op.deprecated }]"
              :aria-label="opLabel(row.op)"
              @click="choose(row.op)" @keydown="onOpKeydown" v-tip.hover="row.op.deprecated ? (row.op.summary + ' (deprecated)') : row.op.summary">
              <span class="op-line">
                <span class="op-method"><template v-for="(p, k) in methodParts(row)" :key="k"><mark v-if="p.mark">{{ p.t }}</mark><template v-else>{{ p.t }}</template></template></span>
                <span class="op-path"><template v-for="(p, k) in pathParts(row)" :key="k"><mark v-if="p.mark">{{ p.t }}</mark><template v-else>{{ p.t }}</template></template></span>
                <span v-if="row.op.deprecated" class="dep-tag sidebar-dep">depr</span>
              </span>
              <span v-if="snippetParts(row)" class="op-snippet"><template v-for="(p, k) in snippetParts(row)" :key="k"><mark v-if="p.mark">{{ p.t }}</mark><template v-else>{{ p.t }}</template></template></span>
            </button>
          </div>
        </template>
        <template v-else>
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
        </template>
        <div v-if="loading" class="hint">Loading spec…</div>
        <div v-else-if="!flatOps.length" class="hint">No operations match.</div>
      </div>
      <div class="sidebar-foot" v-if="brandingShow || footerItems.length">
        <template v-if="brandingShow">
          <span class="foot-brand"><span class="foot-name">Spyglass</span> · OpenAPI Explorer</span>
          <span class="foot-meta">
            <span v-if="version" class="foot-version">v{{ version }}</span>
            <a class="foot-link" href="https://github.com/vdenisov/spyglass" target="_blank" rel="noopener"
               v-tip="'github.com/vdenisov/spyglass'">GitHub ↗</a>
          </span>
        </template>
        <component v-for="(item, i) in footerItems" :is="item" :key="'f' + i" class="foot-item" />
      </div>
    </aside>
  `
}
