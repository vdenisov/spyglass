import { computed } from 'vue'
import { addArrayItem, addMapEntry, selectVariant } from '../spec.js'
import { mdInline } from '../markdown.js'
import { formatBytes } from '../format.js'

// Renders one schema slot as a form control, recursing into object properties and array items.
// Registered globally (see app.js) so the template can reference <SchemaField> recursively.
export default {
  name: 'SchemaField',
  props: {
    node: { type: Object, required: true },
    label: { type: String, default: '' },
    required: { type: Boolean, default: false },
    ph: { type: String, default: '' },
    // When set on the root object, its direct (top-level) string/number children get value history:
    // rootHist(fieldKey) → string[]; rootForget(fieldKey, value) deletes one. histValues / histForget
    // are the resolved per-child suggestion list and delete handler.
    rootHist: { type: Function, default: null },
    rootForget: { type: Function, default: null },
    histValues: { type: Array, default: null },
    histForget: { type: Function, default: null }
  },
  setup(props) {
    // Dates use a plain text input rather than a native picker so any value — a service-specific
    // format, or even an invalid one — is sent verbatim (a general API-testing requirement). The
    // placeholder hints the expected shape: a spec-provided example, then a spec-provided format,
    // falling back to ISO 8601 with millisecond precision for date-time. A spec `default` still
    // prefills the value; a spec `example` is surfaced only as a hint (placeholder + tooltip).
    const inputType = computed(() => props.node.format === 'password' ? 'password' : 'text')
    const FORMAT_HINTS = { 'date-time': '2026-01-02T15:04:05.000Z', date: '2026-01-02' }
    // A scalar example as text; arrays become a comma list, objects JSON.
    const fmtExample = (v) => Array.isArray(v) ? v.map(x => (x && typeof x === 'object') ? JSON.stringify(x) : String(x)).join(', ')
      : (v !== null && typeof v === 'object') ? JSON.stringify(v) : String(v)
    const exampleHint = computed(() => props.node.example === undefined ? '' : fmtExample(props.node.example))
    const exampleTip = computed(() => props.node.example === undefined ? '' : 'Example: ' + fmtExample(props.node.example))
    const placeholder = computed(() => exampleHint.value || props.ph || FORMAT_HINTS[props.node.format] || props.node.format || 'string')
    const numberPlaceholder = computed(() => exampleHint.value || props.ph || (props.node.integer ? 'integer' : 'number'))
    const arrayPlaceholder = computed(() => {
      if (exampleHint.value) return exampleHint.value
      const k = props.node.itemKind
      return k === 'integer' || k === 'number' ? 'one number per line' : 'one value per line'
    })
    const arrayTitle = computed(() => {
      const base = 'One entry per line'
      return props.node.itemEnum ? base + '. Allowed: ' + props.node.itemEnum.join(', ') : base
    })
    const add = () => addArrayItem(props.node)
    const addEntry = () => addMapEntry(props.node)
    const pick = (e) => selectVariant(props.node, Number(e.target.value))
    const variantHint = computed(() => props.node.keyword === 'oneOf'
      ? 'oneOf — the value must match exactly one of these variants'
      : 'anyOf — the value may match one or more of these variants')

    // File inputs: keep the picked File objects on the node (the multipart serializer reads them).
    // A multi-file input accumulates across selections — so files from different folders can be added
    // in steps — and the native input is reset after each pick so the same file can be re-added.
    const onFile = (e) => {
      const picked = Array.from(e.target.files || [])
      if (props.node.multiple) {
        const merged = (props.node.files || []).slice()
        for (const f of picked) {
          if (!merged.some(g => g.name === f.name && g.size === f.size && g.lastModified === f.lastModified)) merged.push(f)
        }
        props.node.files = merged
        e.target.value = ''
      } else {
        props.node.files = picked
      }
      if (!props.required) props.node.include = props.node.files.length > 0
    }
    const removeFile = (i) => {
      props.node.files.splice(i, 1)
      if (!props.required) props.node.include = props.node.files.length > 0
    }
    const fileSize = (f) => formatBytes(f.size || 0)

    return { inputType, placeholder, numberPlaceholder, arrayPlaceholder, arrayTitle, exampleTip, add, addEntry, pick, variantHint, onFile, removeFile, fileSize, mdInline }
  },
  template: `
    <div class="field" :class="'kind-' + node.kind">

      <template v-if="node.kind === 'object'">
        <div v-if="label" class="field-label">
          <input v-if="!required" type="checkbox" class="include" v-model="node.include" v-tip="'include this object'" />
          {{ label }}<span v-if="required" class="req">*</span> <span class="type-tag" v-tip="'An object value — its fields are listed below.'">object</span>
        </div>
        <div class="object-body" :class="{ nested: !!label, inactive: !!label && !required && !node.include }">
          <p v-if="node.description" class="field-desc" v-html="mdInline(node.description)"></p>
          <SchemaField v-for="f in node.fields" :key="f.key" :node="f.node" :label="f.key" :required="f.required"
            :hist-values="rootHist ? rootHist(f.key) : null"
            :hist-forget="rootForget ? (v) => rootForget(f.key, v) : null" />
          <p v-if="!node.fields.length" class="hint">(no properties)</p>
        </div>
      </template>

      <template v-else-if="node.kind === 'array'">
        <div class="field-label">{{ label }}<span v-if="required" class="req">*</span> <span class="type-tag" v-tip="arrayTitle">array</span></div>
        <p v-if="node.description" class="field-desc" v-html="mdInline(node.description)"></p>
        <div class="array-body">
          <template v-if="node.primitive">
            <textarea class="array-text" v-model="node.text" rows="3" :placeholder="arrayPlaceholder" v-tip="arrayTitle" spellcheck="false"></textarea>
          </template>
          <template v-else>
            <div class="array-item" v-for="(it, i) in node.items" :key="it._key">
              <div class="array-item-head">
                <span class="array-index">[{{ i }}]</span>
                <button class="btn-mini danger" type="button" @click="node.items.splice(i, 1)" v-tip="'remove'" aria-label="remove item">✕</button>
              </div>
              <SchemaField :node="it" :required="true" />
            </div>
            <button class="btn-mini add" type="button" @click="add">+ Add item</button>
          </template>
        </div>
      </template>

      <template v-else-if="node.kind === 'map'">
        <div class="field-label">{{ label }}<span v-if="required" class="req">*</span> <span class="type-tag" v-tip="'A free-form map — add key/value entries (schema additionalProperties).'">map</span></div>
        <p v-if="node.description" class="field-desc" v-html="mdInline(node.description)"></p>
        <div class="map-body">
          <div class="map-entry" v-for="(e, i) in node.entries" :key="e._key">
            <input class="map-key" v-model="e.key" placeholder="key" />
            <div class="map-value"><SchemaField :node="e.node" :required="true" ph="value" /></div>
            <button class="btn-mini danger" type="button" @click="node.entries.splice(i, 1)" v-tip="'remove'" aria-label="remove entry">✕</button>
          </div>
          <button class="btn-mini add" type="button" @click="addEntry">+ Add entry</button>
        </div>
      </template>

      <template v-else-if="node.kind === 'string'">
        <div class="field-row">
          <span v-if="label" class="field-label">{{ label }}<span v-if="required" class="req">*</span></span>
          <span class="control">
            <input v-if="!required" type="checkbox" class="include" v-model="node.include" v-tip="'include this field'" />
            <ComboBox :type="inputType" v-model="node.value" :disabled="!required && !node.include" :placeholder="placeholder"
                      :minlength="node.minLength" :maxlength="node.maxLength" :pattern="node.pattern || null"
                      :options="histValues || []" :deletable="!!histForget" @remove="histForget" v-tip="exampleTip" />
            <span v-if="node.constraints" class="constraint" v-tip="node.pattern ? 'Must match pattern: ' + node.pattern : ''">{{ node.constraints }}</span>
          </span>
        </div>
        <p v-if="node.description" class="field-desc" v-html="mdInline(node.description)"></p>
      </template>

      <template v-else-if="node.kind === 'number'">
        <div class="field-row">
          <span v-if="label" class="field-label">{{ label }}<span v-if="required" class="req">*</span></span>
          <span class="control">
            <ComboBox type="number" v-model="node.value" :step="node.multipleOf || (node.integer ? '1' : 'any')"
                      :min="node.minimum" :max="node.maximum" :options="histValues || []" :deletable="!!histForget" @remove="histForget"
                      :placeholder="numberPlaceholder" v-tip="exampleTip" />
            <span v-if="node.constraints" class="constraint">{{ node.constraints }}</span>
          </span>
        </div>
        <p v-if="node.description" class="field-desc" v-html="mdInline(node.description)"></p>
      </template>

      <template v-else-if="node.kind === 'boolean'">
        <div class="field-row">
          <span v-if="label" class="field-label">{{ label }}<span v-if="required" class="req">*</span></span>
          <span class="control">
            <input v-if="!required" type="checkbox" class="include" v-model="node.include" v-tip="'include this field'" />
            <select v-model="node.value" :disabled="!required && !node.include">
              <option :value="true">true</option>
              <option :value="false">false</option>
            </select>
          </span>
        </div>
        <p v-if="node.description" class="field-desc" v-html="mdInline(node.description)"></p>
      </template>

      <template v-else-if="node.kind === 'enum'">
        <div class="field-row">
          <span v-if="label" class="field-label">{{ label }}<span v-if="required" class="req">*</span></span>
          <span class="control">
            <input v-if="!required" type="checkbox" class="include" v-model="node.include" v-tip="'include this field'" />
            <select v-model="node.value" :disabled="!required && !node.include">
              <option v-if="required" value="">— choose —</option>
              <option v-for="o in node.options" :key="o" :value="o">{{ o }}</option>
            </select>
          </span>
        </div>
        <p v-if="node.description" class="field-desc" v-html="mdInline(node.description)"></p>
      </template>

      <template v-else-if="node.kind === 'variant'">
        <div class="field-label">
          <input v-if="!required" type="checkbox" class="include" v-model="node.include" v-tip="'include this field'" />
          {{ label }}<span v-if="required" class="req">*</span> <span class="type-tag" v-tip="variantHint">{{ node.keyword }}</span>
        </div>
        <p v-if="node.description" class="field-desc" v-html="mdInline(node.description)"></p>
        <div class="variant-body" :class="{ nested: !!label, inactive: !!label && !required && !node.include }">
          <label class="variant-select">
            <span class="variant-select-label">Variant</span>
            <select :value="node.selected" @change="pick" :disabled="!!label && !required && !node.include">
              <option v-for="(v, i) in node.variants" :key="i" :value="i">{{ v.label }}</option>
            </select>
          </label>
          <p v-if="node.keyword === 'anyOf'" class="variant-note">
            anyOf allows more than one branch at once — fill one here, or use Raw JSON to combine several.
          </p>
          <SchemaField :node="node.child" :required="true" />
        </div>
      </template>

      <template v-else-if="node.kind === 'file'">
        <div class="field-row">
          <span v-if="label" class="field-label">{{ label }}<span v-if="required" class="req">*</span> <span class="type-tag" v-tip="node.multiple ? 'One or more files (sent as multipart/form-data)' : 'A file (sent as multipart/form-data)'">file</span></span>
          <span class="control">
            <label class="file-picker">
              <input type="file" class="file-input" :multiple="node.multiple" @change="onFile" />
              <span class="file-btn">{{ node.multiple ? 'Choose files…' : 'Choose file…' }}</span>
            </label>
          </span>
        </div>
        <ul v-if="node.files && node.files.length" class="file-list">
          <li v-for="(f, i) in node.files" :key="i">
            <span class="file-name">{{ f.name }}</span><span class="file-size">({{ fileSize(f) }})</span>
            <button class="btn-mini danger" type="button" @click="removeFile(i)" v-tip="'remove'" aria-label="remove file">✕</button>
          </li>
        </ul>
        <p v-if="node.description" class="field-desc" v-html="mdInline(node.description)"></p>
      </template>

      <div v-else class="unsupported">
        <span class="field-label" v-if="label">{{ label }}</span>
        ⚠ Can't render as a form ({{ node.reason }}) — use raw JSON.
      </div>

    </div>
  `
}
