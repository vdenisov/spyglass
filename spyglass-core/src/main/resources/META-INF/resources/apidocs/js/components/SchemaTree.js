import { computed } from 'vue'
import { mdBlock, mdInline } from '../markdown.js'

// Read-only documentation view of a schema tree (see spec.js#schemaTree).
// Registered globally (app.js) so the template can recurse via <SchemaTree>.
export default {
  name: 'SchemaTree',
  props: {
    node: { type: Object, required: true },
    name: { type: String, default: '' },
    required: { type: Boolean, default: false }
  },
  setup(props) {
    const typeText = computed(() => props.node.enumValues ? 'enum: ' + props.node.enumValues.join(', ') : props.node.typeLabel)
    const hasFields = computed(() => props.node.fields && props.node.fields.length > 0)
    return { typeText, hasFields, mdBlock, mdInline }
  },
  template: `
    <div class="stree">
      <div v-if="!name && node.description" class="stree-rootdesc" v-html="mdBlock(node.description)"></div>
      <div v-if="name" class="stree-row">
        <span class="stree-name">{{ name }}</span>
        <span class="stree-type">{{ typeText }}</span>
        <span v-if="node.constraints" class="stree-constraint">{{ node.constraints }}</span>
        <span v-if="required" class="stree-req">required</span>
        <span v-if="node.deprecated" class="dep-tag">deprecated</span>
        <span v-if="node.description" class="stree-desc" v-html="mdInline(node.description)"></span>
      </div>
      <div v-else-if="node.typeLabel !== 'object'" class="stree-row">
        <span class="stree-type">{{ typeText }}</span>
        <span v-if="node.constraints" class="stree-constraint">{{ node.constraints }}</span>
      </div>
      <div v-if="node.variants" class="stree-variants" :class="{ root: !name }">
        <div v-for="(v, i) in node.variants" :key="i" class="stree-variant">
          <div class="stree-variant-label">{{ v.label }}</div>
          <SchemaTree :node="v.node" />
        </div>
      </div>
      <div v-if="hasFields" class="stree-children" :class="{ root: !name }">
        <SchemaTree v-for="f in node.fields" :key="f.name" :node="f.node" :name="f.name" :required="f.required" />
        <div v-if="node.additionalType" class="stree-row stree-additional">
          <span class="stree-name">additional properties</span>
          <span class="stree-type">{{ node.additionalType }}</span>
        </div>
      </div>
      <div v-else-if="!name && node.typeLabel === 'object'" class="hint">(no documented properties)</div>
    </div>
  `
}
