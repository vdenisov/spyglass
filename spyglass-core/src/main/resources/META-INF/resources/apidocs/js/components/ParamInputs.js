import { mdInline } from '../markdown.js'

// Renders path / query / header parameters of an operation as typed inputs.
// The `params` array is owned by OperationPanel; this component is presentational.
export default {
  name: 'ParamInputs',
  props: {
    params: { type: Array, required: true },
    // (param) => string[] of previously-used values for the field's combobox suggestions.
    history: { type: Function, default: () => [] },
    // (param, value) => void: forget one remembered value for the field.
    forget: { type: Function, default: () => {} }
  },
  setup() {
    const groups = ['path', 'query', 'header']
    const labels = { path: 'Path parameters', query: 'Query parameters', header: 'Header parameters' }
    const inGroup = (params, g) => params.filter(p => p.in === g)
    // A spec `example` becomes a placeholder hint (and a tooltip backstop); it never prefills the value.
    const phFor = (p) => p.example !== undefined && p.example !== null ? String(p.example) : p.placeholder
    const tipFor = (p) => p.example !== undefined && p.example !== null ? 'Example: ' + String(p.example) : ''
    // Array params render like the body's primitive-array control (label + `array` tag + one-per-line
    // textarea); the kept tag/tooltip keep the "one entry per line" affordance visible after the field
    // is filled. These mirror SchemaField's arrayTitle/arrayPlaceholder.
    const arrayTitle = (p) => p.itemEnum ? 'One entry per line. Allowed: ' + p.itemEnum.join(', ') : 'One entry per line'
    const arrayPlaceholder = (p) => {
      if (p.example !== undefined && p.example !== null) return Array.isArray(p.example) ? p.example.join(', ') : String(p.example)
      return p.itemKind === 'integer' || p.itemKind === 'number' ? 'one number per line' : 'one value per line'
    }
    return { groups, labels, inGroup, phFor, tipFor, arrayTitle, arrayPlaceholder, mdInline }
  },
  template: `
    <div class="params">
      <template v-for="g in groups" :key="g">
        <div v-if="params.some(p => p.in === g)" class="param-group">
          <h3>{{ labels[g] }}</h3>
          <div v-for="p in inGroup(params, g)" :key="p.name" class="param-field">
          <div v-if="p.control === 'array'" class="param-array">
            <div class="field-label">{{ p.name }}<span v-if="p.required" class="req">*</span><span v-if="p.deprecated" class="dep-tag">deprecated</span> <span class="type-tag" v-tip="arrayTitle(p)">array</span></div>
            <p v-if="p.description" class="field-desc" v-html="mdInline(p.description)"></p>
            <textarea class="array-text" v-model="p.value" rows="3" spellcheck="false"
              :placeholder="arrayPlaceholder(p)" v-tip="arrayTitle(p)"></textarea>
          </div>
          <template v-else>
          <label class="field-row">
            <span class="field-label">{{ p.name }}<span v-if="p.required" class="req">*</span><span v-if="p.deprecated" class="dep-tag">deprecated</span></span>
            <span class="control">
              <select v-if="p.control === 'enum'" v-model="p.value">
                <option value="">— choose —</option>
                <option v-for="o in p.options" :key="o" :value="String(o)">{{ o }}</option>
              </select>
              <select v-else-if="p.control === 'boolean'" v-model="p.value">
                <option value="">— (omit)</option>
                <option value="true">true</option>
                <option value="false">false</option>
              </select>
              <ComboBox v-else type="text" v-model="p.value" :placeholder="phFor(p)" :options="history(p)"
                deletable @remove="forget(p, $event)" v-tip="tipFor(p)" />
            </span>
          </label>
          <p v-if="p.description" class="field-desc" v-html="mdInline(p.description)"></p>
          </template>
          </div>
        </div>
      </template>
    </div>
  `
}
