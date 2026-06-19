import { ref, computed, watch, nextTick, onMounted, onBeforeUnmount } from 'vue'

// Lists operations grouped by tag with a live filter; emits `select` when one is clicked.
// Keyboard: "/" focuses the filter; ↑/↓ move a highlight through the filtered list; Enter opens the
// highlighted op; Escape clears the filter (or blurs when already empty).
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

    // Flattened filtered order (across tag groups) for ↑/↓ navigation; activeIndex is the highlight.
    const flatOps = computed(() => groups.value.flatMap(g => g.ops))
    const activeIndex = ref(0)
    // The keyboard highlight only matters while the filter is focused (where ↑/↓/Enter act). Hiding it
    // otherwise stops the first row from looking "selected" on a passive load, when nothing is selected
    // yet and the panel shows the "Select an operation" placeholder.
    const filterFocused = ref(false)
    const isActive = (op) => filterFocused.value && flatOps.value[activeIndex.value] && flatOps.value[activeIndex.value].id === op.id
    // Re-anchor the highlight to the first match whenever the result set changes.
    watch(flatOps, (list) => { activeIndex.value = list.length ? 0 : -1 })

    const scrollActive = () => nextTick(() => {
      const el = document.querySelector('.op-link.kbd-active')
      if (el) el.scrollIntoView({ block: 'nearest' })
    })

    const onFilterKeydown = (e) => {
      if (e.key === 'ArrowDown') {
        e.preventDefault()
        if (flatOps.value.length) { activeIndex.value = Math.min(activeIndex.value + 1, flatOps.value.length - 1); scrollActive() }
      } else if (e.key === 'ArrowUp') {
        e.preventDefault()
        if (flatOps.value.length) { activeIndex.value = Math.max(activeIndex.value - 1, 0); scrollActive() }
      } else if (e.key === 'Enter') {
        e.preventDefault()
        const op = flatOps.value[activeIndex.value]
        if (op) emit('select', op)
      } else if (e.key === 'Escape') {
        if (filter.value) filter.value = ''
        else if (filterInput.value) filterInput.value.blur()
      }
    }

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

    return { filter, filterInput, groups, isActive, onFilterKeydown, filterFocused }
  },
  template: `
    <aside class="sidebar">
      <div class="brand">{{ title }}</div>
      <input class="filter" ref="filterInput" v-model="filter" @keydown="onFilterKeydown" @focus="filterFocused = true" @blur="filterFocused = false" placeholder="Filter operations…  ( / )" />
      <div class="op-list">
        <div v-for="grp in groups" :key="grp.tag" class="tag-group">
          <div class="tag-name">{{ grp.tag }}</div>
          <button v-for="op in grp.ops" :key="op.id" type="button"
            class="op-link" :class="['m-' + op.method.toLowerCase(), { active: op.id === selectedId, deprecated: op.deprecated, 'kbd-active': isActive(op) }]"
            @click="$emit('select', op)" v-tip="op.deprecated ? (op.summary + ' (deprecated)') : op.summary">
            <span class="op-method">{{ op.method }}</span>
            <span class="op-path">{{ op.path }}</span>
            <span v-if="op.deprecated" class="dep-tag sidebar-dep">dep</span>
          </button>
        </div>
        <div v-if="!groups.length" class="hint">No operations match.</div>
      </div>
    </aside>
  `
}
