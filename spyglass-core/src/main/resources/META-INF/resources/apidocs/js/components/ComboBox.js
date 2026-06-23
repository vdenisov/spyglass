import { ref, computed, useAttrs, onBeforeUnmount } from 'vue'

// Per-instance id seed for the combobox/listbox ARIA wiring (aria-controls / aria-activedescendant).
let comboboxUid = 0

// A themed, editable combobox: a free-text input plus a styled suggestion list we render ourselves
// (so it matches the app theme and, unlike a native <datalist>, shows the full list on demand).
// Clicking the caret opens the complete list; typing filters it. Any extra attributes (type,
// placeholder, disabled, min/max/step, minlength/maxlength/pattern, title) pass through to the input,
// so it can stand in for a plain <input> anywhere. With no options it behaves as a plain input.
//
// Registered globally (app.js) so recursive/global components (SchemaField) can use it.
export default {
  name: 'ComboBox',
  inheritAttrs: false,
  props: {
    // String for most fields; Number for numeric inputs bound with v-model.number (e.g. expiry).
    modelValue: { type: [String, Number], default: '' },
    options: { type: Array, default: () => [] },
    // When true, each suggestion shows a "✕" that emits `remove` (used for value-history fields,
    // not for fixed/spec-derived option lists like Accept).
    deletable: { type: Boolean, default: false }
  },
  emits: ['update:modelValue', 'remove'],
  setup(props, { emit }) {
    const attrs = useAttrs()
    const root = ref(null)
    const isOpen = ref(false)
    const showAll = ref(false)
    const active = ref(-1)
    const disabled = computed(() => attrs.disabled !== undefined && attrs.disabled !== false)
    const listId = 'combobox-' + (++comboboxUid) + '-list'
    const optionId = (i) => listId + '-opt-' + i

    // Open via the caret shows everything; typing narrows by case-insensitive substring (falling
    // back to the full list when nothing matches, so the dropdown never goes empty mid-type).
    const filtered = computed(() => {
      // modelValue may be a Number (numeric fields bind it); coerce before string ops so .toLowerCase()
      // can't throw on a non-zero number.
      const t = String(props.modelValue ?? '').toLowerCase()
      if (showAll.value || !t) return props.options
      const f = props.options.filter(o => String(o).toLowerCase().includes(t))
      return f.length ? f : props.options
    })

    let docHandler = null
    const open = () => {
      if (disabled.value || !props.options.length || isOpen.value) return
      isOpen.value = true
      active.value = -1
      docHandler = (e) => { if (root.value && !root.value.contains(e.target)) close() }
      document.addEventListener('mousedown', docHandler)
    }
    const close = () => {
      isOpen.value = false
      showAll.value = false
      if (docHandler) { document.removeEventListener('mousedown', docHandler); docHandler = null }
    }
    onBeforeUnmount(close)

    const toggle = () => {
      if (disabled.value || !props.options.length) return
      if (isOpen.value) { close(); return }
      showAll.value = true
      open()
    }
    const onInput = (e) => { showAll.value = false; emit('update:modelValue', e.target.value); open() }
    const choose = (o) => { emit('update:modelValue', String(o)); close() }
    // Deleting a suggestion leaves the dropdown open; the host updates history and the list refreshes.
    const remove = (o) => emit('remove', String(o))

    const onKeydown = (e) => {
      if (disabled.value || !props.options.length) return
      if (e.key === 'ArrowDown') {
        e.preventDefault()
        if (!isOpen.value) { showAll.value = true; open() } else active.value = Math.min(active.value + 1, filtered.value.length - 1)
      } else if (e.key === 'ArrowUp') {
        e.preventDefault(); active.value = Math.max(active.value - 1, 0)
      } else if (e.key === 'Enter') {
        if (isOpen.value && active.value >= 0) { e.preventDefault(); choose(filtered.value[active.value]) }
      } else if (e.key === 'Escape') {
        if (isOpen.value) { e.preventDefault(); close() }
      }
    }

    return { root, isOpen, filtered, active, attrs, disabled, listId, optionId, toggle, onInput, choose, remove, onKeydown, open }
  },
  template: `
    <div class="combobox" :class="{ 'has-caret': options.length }" ref="root">
      <input class="combobox-input" :value="modelValue" v-bind="attrs" autocomplete="off"
             :role="options.length ? 'combobox' : null"
             :aria-autocomplete="options.length ? 'list' : null"
             :aria-expanded="options.length ? (isOpen ? 'true' : 'false') : null"
             :aria-controls="options.length && isOpen ? listId : null"
             :aria-activedescendant="isOpen && active >= 0 ? optionId(active) : null"
             @input="onInput" @focus="open" @keydown="onKeydown" />
      <span v-if="options.length" class="combobox-caret" :class="{ disabled }" aria-hidden="true" @mousedown.prevent="toggle"></span>
      <ul v-if="isOpen && filtered.length" :id="listId" class="combobox-list" role="listbox">
        <li v-for="(o, i) in filtered" :key="o" :id="optionId(i)" role="option" :aria-selected="i === active" :class="{ active: i === active }"
            @mousedown.prevent="choose(o)" @mouseenter="active = i">
          <span class="combobox-opt">{{ o }}</span>
          <span v-if="deletable" class="combobox-del" role="button" aria-label="remove suggestion"
                @mousedown.prevent.stop="remove(o)">✕</span>
        </li>
      </ul>
    </div>
  `
}
