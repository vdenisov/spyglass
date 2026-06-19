import { createApp } from 'vue'
import App from './components/App.js'
import SchemaField from './components/SchemaField.js'
import SchemaTree from './components/SchemaTree.js'
import ComboBox from './components/ComboBox.js'
import ExampleCard from './components/ExampleCard.js'
import tooltip from './tooltip.js'

const app = createApp(App)
// Registered globally so their templates can reference themselves recursively (SchemaField/SchemaTree)
// and so any template can use the shared <ComboBox> without importing it.
app.component('SchemaField', SchemaField)
app.component('SchemaTree', SchemaTree)
app.component('ComboBox', ComboBox)
app.component('ExampleCard', ExampleCard)
// Themed hover/focus tooltip for explanatory hints: v-tip="'text'".
app.directive('tip', tooltip)
app.mount('#app')
