import { onMounted, onBeforeUnmount, watch, shallowRef } from 'vue'
import { EditorView, EditorState, basicSetup, json } from 'codemirror-bundle'
import { highlight, surfaceTheme } from '../cmTheme.js'

// Read-only CodeMirror viewer for response bodies. Reuses the editor's theme and basicSetup (which
// gives line numbers, code folding and in-document search) but adds NO linter — it's a real service
// response, so parse/schema linting would only be noise. `language: 'json'` enables JSON
// highlighting + folding; anything else renders as plain text (still searchable/foldable chrome).
export default {
  name: 'CodeViewer',
  props: {
    value: { type: String, default: '' },
    language: { type: String, default: 'text' }
  },
  setup(props) {
    const host = shallowRef(null)
    let view = null

    const extensions = () => {
      const ext = [
        basicSetup,
        EditorState.readOnly.of(true),
        EditorView.editable.of(false),
        EditorView.lineWrapping,
        highlight,
        surfaceTheme
      ]
      if (props.language === 'json') ext.push(json())
      return ext
    }

    const rebuild = () => {
      if (view) { view.destroy(); view = null }
      view = new EditorView({ parent: host.value, state: EditorState.create({ doc: props.value || '', extensions: extensions() }) })
    }

    onMounted(rebuild)
    onBeforeUnmount(() => { if (view) { view.destroy(); view = null } })

    // The document is read-only, so a value change replaces it wholesale.
    watch(() => props.value, (val) => {
      if (!view) return
      const cur = view.state.doc.toString()
      if (val === cur) return
      view.dispatch({ changes: { from: 0, to: cur.length, insert: val || '' } })
    })
    // A language switch (json <-> text) requires rebuilding with different extensions.
    watch(() => props.language, rebuild)

    return { host }
  },
  template: `<div class="code-viewer" ref="host"></div>`
}
