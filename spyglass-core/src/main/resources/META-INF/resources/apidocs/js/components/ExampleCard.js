import { ref, computed } from 'vue'
import { mdInline } from '../markdown.js'
import { highlightJson, escapeHtml } from '../jsonHighlight.js'

// Renders one OpenAPI example (named or singular) as a readable card on the Schema → Examples tab:
// its name, summary, markdown description and pretty-printed (syntax-highlighted) value. An example
// carrying an `externalValue` (a URL instead of an inline value) is shown as a link rather than a
// body. When `canPrefill` is set the card shows a button (labelled by `prefillLabel`) that emits
// `prefill` with the raw value — the host applies it (request body → Raw editor; parameter → field) —
// and the card flashes a brief confirmation.
//
// Registered globally (app.js) so any template (OperationPanel) can use <ExampleCard>.
export default {
  name: 'ExampleCard',
  props: {
    name: { type: String, default: 'Example' },
    summary: { type: String, default: '' },
    description: { type: String, default: '' },
    value: { default: undefined },
    externalValue: { type: String, default: '' },
    canPrefill: { type: Boolean, default: false },
    prefillLabel: { type: String, default: 'Prefill Raw JSON' }
  },
  emits: ['prefill'],
  setup(props, { emit }) {
    const isJson = computed(() => props.value !== null && typeof props.value === 'object')
    const text = computed(() => {
      if (props.externalValue) return ''
      const v = props.value
      return isJson.value ? JSON.stringify(v, null, 2) : (v == null ? '' : String(v))
    })
    const html = computed(() => isJson.value ? highlightJson(text.value) : escapeHtml(text.value))
    const showPrefill = computed(() => props.canPrefill && !props.externalValue)

    const applied = ref(false)
    let timer = null
    const apply = () => {
      emit('prefill', props.value)
      applied.value = true
      clearTimeout(timer)
      timer = setTimeout(() => { applied.value = false }, 1500)
    }
    return { text, html, showPrefill, applied, apply, mdInline }
  },
  template: `
    <div class="example-card">
      <div class="example-card-head">
        <span class="example-name">{{ name }}</span>
        <span v-if="summary" class="example-summary">{{ summary }}</span>
        <span v-if="showPrefill" class="example-apply-cluster">
          <span v-if="applied" class="example-applied" role="status">✓ Applied</span>
          <button type="button" class="btn-mini example-prefill" @click="apply"
                  v-tip="'Apply this example'">{{ prefillLabel }}</button>
        </span>
      </div>
      <div v-if="description" class="example-desc" v-html="mdInline(description)"></div>
      <a v-if="externalValue" class="example-ext" :href="externalValue" target="_blank" rel="noopener">{{ externalValue }} ↗</a>
      <pre v-else-if="text" class="example-json" v-html="html"></pre>
    </div>
  `
}
