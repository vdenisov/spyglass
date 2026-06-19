// Bundle entry for the API explorer's CodeMirror editor. esbuild bundles this and its
// dependency graph into one self-contained ESM file (../src/main/resources/META-INF/resources/apidocs/vendor/codemirror.bundle.js)
// so the no-build, importmap-based explorer can load a single module with one copy of
// @codemirror/state — avoiding the duplicate-instance breakage that separate CDN modules cause.
//
// Export what js/components/JsonEditor.js (editor) and js/components/CodeViewer.js (read-only
// response viewer) consume.
export { EditorView, basicSetup } from "codemirror";
export { EditorState, Compartment } from "@codemirror/state";
export { lintGutter } from "@codemirror/lint";
export { jsonSchema } from "codemirror-json-schema";
// Plain JSON language (no linter) for the read-only response viewer — jsonSchema() bundles the
// parse/schema linter, which is noise on a real service response. Already a bundle dependency.
export { json } from "@codemirror/lang-json";
// Syntax-highlight primitives so the explorer can supply a theme-aware (CSS-variable-driven)
// highlight style; both packages are already bundled (via basicSetup / lang-json), so this
// adds no new dependency.
export { HighlightStyle, syntaxHighlighting } from "@codemirror/language";
export { tags } from "@lezer/highlight";
