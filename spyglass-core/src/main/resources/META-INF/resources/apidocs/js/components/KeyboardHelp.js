import { SHORTCUTS } from '../shortcuts.js'
import Modal from './Modal.js'

// Platform-aware label for the "Mod" sentinel in the catalog: ⌘ on macOS, Ctrl elsewhere. Same
// detection as OperationPanel.js's send hint (modern userAgentData.platform, then the deprecated
// navigator.platform, then the UA string).
const uaPlatform = navigator.userAgentData?.platform || navigator.platform || navigator.userAgent || ''
const isMac = /Mac|iPhone|iPad/.test(uaPlatform)
const keyLabel = (k) => k === 'Mod' ? (isMac ? '⌘' : 'Ctrl') : k

// The keyboard-shortcuts help overlay, opened with "?" (see App.js). Presentational: it lays out the
// static SHORTCUTS catalog (shortcuts.js — the single source of truth) inside the reusable <Modal> and
// forwards its close. App owns the open state and the "?" hotkey; the dialog mechanics live in Modal.
export default {
  name: 'KeyboardHelp',
  components: { Modal },
  props: {
    show: { type: Boolean, default: false }
  },
  emits: ['close'],
  setup() {
    return { sections: SHORTCUTS, keyLabel }
  },
  template: `
    <Modal :show="show" title="Keyboard shortcuts" @close="$emit('close')">
      <div class="shortcuts">
        <section v-for="group in sections" :key="group.section" class="shortcuts-section">
          <h3 class="shortcuts-heading">{{ group.section }}</h3>
          <dl class="shortcuts-list">
            <div v-for="(item, i) in group.items" :key="i" class="shortcuts-row">
              <dt class="shortcut-keys">
                <kbd v-for="(k, j) in item.keys" :key="j">{{ keyLabel(k) }}</kbd>
              </dt>
              <dd class="shortcut-desc">{{ item.desc }}</dd>
            </div>
          </dl>
        </section>
      </div>
    </Modal>
  `
}
