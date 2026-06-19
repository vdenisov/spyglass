import { onMounted, onBeforeUnmount, watch, shallowRef } from 'vue'
import { EditorView, EditorState, Compartment, basicSetup, lintGutter, jsonSchema } from 'codemirror-bundle'
import { highlight, surfaceTheme } from '../cmTheme.js'

// A CodeMirror 6 JSON editor with schema-aware autocomplete, hover docs, and inline
// validation (both JSON syntax errors and schema discrepancies). Two-way bound via
// v-model; the active schema can change as the user switches operations.
export default {
  name: 'JsonEditor',
  props: {
    modelValue: { type: String, default: '' },
    schema: { type: Object, default: null }
  },
  emits: ['update:modelValue'],
  setup(props, { emit }) {
    const host = shallowRef(null)
    let view = null
    // Guards the update listener while we push an external value into the doc, so
    // programmatic edits don't echo back out as user input.
    let applyingExternal = false
    const schemaSlot = new Compartment()

    // jsonSchema() bundles lang-json, the schema linter, hover tooltips and the
    // schema-aware completion source. An empty schema still yields JSON syntax
    // highlighting and parse-error linting.
    const schemaExtension = () => jsonSchema(props.schema || {})

    onMounted(() => {
      view = new EditorView({
        parent: host.value,
        state: EditorState.create({
          doc: props.modelValue || '',
          extensions: [
            basicSetup,
            // jsonSchema() wires up lang-json, a JSON parse linter and the schema linter, so both
            // syntax errors and schema discrepancies are highlighted inline.
            schemaSlot.of(schemaExtension()),
            lintGutter(),
            highlight,
            surfaceTheme,
            EditorView.updateListener.of((u) => {
              if (u.docChanged && !applyingExternal) emit('update:modelValue', u.state.doc.toString())
            })
          ]
        })
      })
    })

    onBeforeUnmount(() => { if (view) { view.destroy(); view = null } })

    // External edits (Pretty-print, importing the form payload) → push into the editor.
    watch(() => props.modelValue, (val) => {
      if (!view) return
      const cur = view.state.doc.toString()
      if (val === cur) return
      applyingExternal = true
      view.dispatch({ changes: { from: 0, to: cur.length, insert: val || '' } })
      applyingExternal = false
    })

    // Operation switch → swap the active schema without rebuilding the editor.
    watch(() => props.schema, () => {
      if (view) view.dispatch({ effects: schemaSlot.reconfigure(schemaExtension()) })
    })

    return { host }
  },
  template: `<div class="json-editor" ref="host"></div>`
}
