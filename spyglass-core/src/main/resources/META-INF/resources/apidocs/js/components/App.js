import { ref, computed, onMounted, nextTick, watch } from 'vue'
import { CONFIG, storageKey } from '../config.js'
import { loadSpec, collectOperations } from '../spec.js'
import { loadJson, saveJson, clearSaved, HEADERS_KEY, AUTH_TOKEN_KEY, SIDEBAR_WIDTH_KEY, ACCEPT_KEY } from '../storage.js'
import { getValues, recordValue, removeValue, authKey } from '../history.js'
import { registry, registerAuthPanel, registerHeaderPresets, registerHeaderLinkResolver, loadExtensions } from '../extensions.js'
import Sidebar from './Sidebar.js'
import OperationPanel from './OperationPanel.js'
import ThemeToggle from './ThemeToggle.js'

const MIN_SIDEBAR = 240

// Root component: loads the spec, owns the base-URL and global headers, and shows the selected operation.
// Consumer-specific UI (an Authorization-header generator, header presets) is contributed by
// front-end extensions through the seam (see extensions.js); the core ships none of it.
export default {
  name: 'App',
  components: { Sidebar, OperationPanel, ThemeToggle },
  setup() {
    const loading = ref(true)
    const error = ref('')
    const title = ref('API')
    const operations = ref([])
    const selected = ref(null)
    // Requests always go to the origin that served the explorer (same-origin; cross-origin
    // would be blocked by CORS anyway), so the base URL is fixed, not editable.
    const baseUrl = ref(window.location.origin)

    // Hydrate persisted header rows. Their Authorization value is not stored here (it's a
    // short-lived token kept in sessionStorage); overlay it back onto the Authorization row.
    const storedHeaders = loadJson(localStorage, HEADERS_KEY, null)
    const initialHeaders = Array.isArray(storedHeaders) && storedHeaders.length
      ? storedHeaders.map(h => ({ key: h.key || '', value: h.value || '', ph: h.ph, hint: h.hint }))
      : [{ key: 'Authorization', value: '' }]
    const storedToken = loadJson(sessionStorage, AUTH_TOKEN_KEY, '') || ''
    const authRow = initialHeaders.find(h => (h.key || '').toLowerCase() === 'authorization')
    if (authRow) authRow.value = storedToken
    else initialHeaders.unshift({ key: 'Authorization', value: storedToken })
    const headers = ref(initialHeaders)

    // A stored sidebar width wins over the measured default (applied/clamped in onMounted).
    const storedWidth = loadJson(localStorage, SIDEBAR_WIDTH_KEY, null)
    const sidebarWidth = ref(typeof storedWidth === 'number' ? storedWidth : 320)
    // Bumped on "Clear" (exposed as api.headers.resetSignal) so an extension's auth panel resets its
    // own form state.
    const authResetSeq = ref(0)

    // The Accept header (response-format negotiation), applied to every try-it-out request.
    // application/json yields readable JSON errors; */* (browser default) returns the Thrift-encoded
    // TError. Defaults to application/json and persists.
    const accept = ref(loadJson(localStorage, ACCEPT_KEY, 'application/json'))
    watch(accept, (v) => saveJson(localStorage, ACCEPT_KEY, v))
    // Suggestions: the selected operation's declared response content types, plus common values.
    const acceptOptions = computed(() => {
      const set = new Set(['application/json', '*/*'])
      const responses = selected.value && selected.value.responses
      if (responses) for (const resp of Object.values(responses)) {
        if (resp && resp.content) for (const ct of Object.keys(resp.content)) set.add(ct)
      }
      return [...set]
    })
    // The content types an operation can return on success (2xx) — what Accept must be compatible with.
    const successProduced = (op) => {
      const out = []
      for (const [status, resp] of Object.entries((op && op.responses) || {})) {
        if (!/^2/.test(status) || !(resp && resp.content)) continue
        for (const ct of Object.keys(resp.content)) if (!out.includes(ct)) out.push(ct)
      }
      return out
    }
    const acceptSatisfiable = (value, produced) => {
      if (!value || value === '*/*' || !produced.length || produced.includes('*/*') || produced.includes(value)) return true
      return value.includes('json') && produced.some(p => p.includes('json')) // a JSON Accept is met by any JSON-ish type
    }
    // Preselect a compatible Accept when the chosen operation can't satisfy the current value — e.g. an
    // image/binary download would 406 with application/json. The user's value is kept whenever the
    // endpoint can satisfy it (so a global application/json / */* preference still sticks for JSON APIs).
    watch(selected, (op) => {
      const produced = successProduced(op)
      if (produced.length && !acceptSatisfiable(accept.value, produced)) accept.value = produced[0]
    })
    const addHeader = () => headers.value.push({ key: '', value: '' })
    const removeHeader = (i) => headers.value.splice(i, 1)

    // Header presets: an extension may contribute named header groups (see extensions.js); selecting
    // one adds a row with the correct wire name and any format hint pre-filled.
    const headerToAdd = ref('')
    const addPreset = (name) => {
      for (const group of registry.headerPresets) {
        const def = group.items.find(i => i.name === name)
        if (def) { headers.value.push({ key: def.name, value: '', ph: def.ph, hint: def.hint }); break }
      }
      headerToAdd.value = ''
    }

    // Bridge for an extension's auth panel (via api.headers): it reads the current Authorization value
    // (to reflect a manually entered one into its form) and calls setAuthorization to set it.
    const authorizationValue = computed(() => {
      const row = headers.value.find(h => (h.key || '').toLowerCase() === 'authorization')
      return row ? row.value : ''
    })
    const setAuthorization = (value) => {
      const row = headers.value.find(h => (h.key || '').toLowerCase() === 'authorization')
      if (row) row.value = value
      else headers.value.unshift({ key: 'Authorization', value })
    }

    // The seam context handed to each extension's register(api). It exposes the loaded spec (for the
    // extension to read its own x-* info extensions), the header bridge (add rows, read/set the
    // Authorization value, observe Clear-all), persistence/history helpers (so extensions don't import
    // core modules by path), and the UI registration hooks.
    const buildExtensionApi = (spec) => ({
      spec,
      config: CONFIG,
      headers: {
        add: (row) => headers.value.push({ key: row.key || '', value: row.value || '', ph: row.ph, hint: row.hint }),
        authorization: authorizationValue,
        setAuthorization,
        resetSignal: authResetSeq
      },
      storage: { key: storageKey, load: loadJson, save: saveJson },
      history: { values: getValues, record: recordValue, remove: removeValue, key: authKey },
      ui: { registerAuthPanel, registerHeaderPresets, registerHeaderLinkResolver }
    })

    // The URL hash is the source of truth for the current selection: #<METHOD>-<path>.
    const anchorFor = (op) => `${op.method}-${op.path}`
    const select = (op) => { window.location.hash = anchorFor(op) }
    const applyHash = () => {
      const raw = window.location.hash.replace(/^#/, '')
      if (!raw) { selected.value = null; return }
      let decoded
      try { decoded = decodeURIComponent(raw) } catch (e) { decoded = raw }
      const i = decoded.indexOf('-')
      const op = i < 0 ? null : operations.value.find(o => o.method === decoded.slice(0, i) && o.path === decoded.slice(i + 1))
      if (op) {
        selected.value = op
        nextTick(() => { const el = document.querySelector('.op-link.active'); if (el) el.scrollIntoView({ block: 'nearest' }) })
      } else {
        // Invalid anchor: render nothing and erase it.
        selected.value = null
        history.replaceState(null, '', window.location.pathname + window.location.search)
      }
    }

    // The sidebar may not exceed half the viewport, and never shrinks below MIN_SIDEBAR.
    const clampWidth = (px) => Math.min(Math.max(px, MIN_SIDEBAR), Math.floor(window.innerWidth * 0.5))

    // Default width = widest operation row (so paths don't wrap), capped at 50%.
    const measureSidebar = () => {
      let max = 0
      document.querySelectorAll('.op-link').forEach(l => { if (l.scrollWidth > max) max = l.scrollWidth })
      if (max > 0) sidebarWidth.value = clampWidth(max + 28)
    }

    const startDrag = () => {
      document.body.style.userSelect = 'none'
      document.body.style.cursor = 'col-resize'
      const onMove = (ev) => { sidebarWidth.value = clampWidth(ev.clientX) }
      const onUp = () => {
        document.body.style.userSelect = ''
        document.body.style.cursor = ''
        window.removeEventListener('mousemove', onMove)
        window.removeEventListener('mouseup', onUp)
      }
      window.addEventListener('mousemove', onMove)
      window.addEventListener('mouseup', onUp)
    }

    onMounted(async () => {
      try {
        const spec = await loadSpec(CONFIG.specUrl)
        if (spec.info && spec.info.title) { title.value = spec.info.title; document.title = spec.info.title }
        operations.value = collectOperations()
        // Front-end extensions: query/global lists (resolved in config.js) win; otherwise the spec may
        // advertise modules via the x-spyglass-extensions info extension. Each module's register(api)
        // contributes UI (an auth panel, header presets) through the seam.
        const specExtensions = (spec.info && spec.info['x-spyglass-extensions']) || []
        if (!CONFIG.extensions.length && Array.isArray(specExtensions)) CONFIG.extensions = specExtensions
        await loadExtensions(buildExtensionApi(spec))
        await nextTick()
        // A persisted width wins; otherwise fall back to the measured (widest-row) default.
        if (storedWidth == null) measureSidebar()
        else sidebarWidth.value = clampWidth(sidebarWidth.value)
        applyHash()
      } catch (e) {
        error.value = e.message
      } finally {
        loading.value = false
      }
      window.addEventListener('hashchange', applyHash)
      window.addEventListener('resize', () => { sidebarWidth.value = clampWidth(sidebarWidth.value) })
    })

    // Persist request state on change. Authorization value → sessionStorage (short-lived token);
    // the remaining header rows → localStorage. Sidebar width → localStorage.
    watch(headers, () => {
      const rows = headers.value.map(h =>
        (h.key || '').toLowerCase() === 'authorization' ? { ...h, value: '' } : { ...h })
      saveJson(localStorage, HEADERS_KEY, rows)
      saveJson(sessionStorage, AUTH_TOKEN_KEY, authorizationValue.value)
    }, { deep: true })
    watch(sidebarWidth, (w) => saveJson(localStorage, SIDEBAR_WIDTH_KEY, w))

    // "Clear all": reset the request inputs — the header rows (to a single empty Authorization row,
    // needed on nearly every request), the auth form and Accept — and wipe their persisted copies.
    // Field history, the sidebar width (layout) and UI preferences (theme, response-pretty) are kept.
    const clearAll = () => {
      clearSaved()
      headers.value = [{ key: 'Authorization', value: '' }]
      accept.value = 'application/json'
      authResetSeq.value++
    }

    return {
      loading, error, title, operations, selected, baseUrl, headers, sidebarWidth,
      authorizationValue, setAuthorization, authResetSeq,
      accept, acceptOptions,
      addHeader, removeHeader, select, startDrag, clearAll,
      headerPresets: registry.headerPresets, authPanels: registry.authPanels, headerToAdd, addPreset
    }
  },
  template: `
    <div class="layout">
      <Sidebar :operations="operations" :selected-id="selected ? selected.id : ''" :title="title" @select="select"
        :style="{ flex: '0 0 ' + sidebarWidth + 'px', width: sidebarWidth + 'px' }" />
      <div class="divider" @mousedown.prevent="startDrag" v-tip="'Drag to resize'"></div>
      <main class="main">
        <div class="topbar-wrap">
        <div class="topbar">
          <div class="topbar-actions">
            <button class="btn-mini danger btn-clear-all" type="button" @click="clearAll"
              v-tip="'Clears the shared request settings — the global headers, the auth form and Accept. Per-operation inputs (path, query, body), field history, layout and theme are kept.'">Clear all</button>
            <ThemeToggle />
          </div>
          <div class="topbar-main">
          <div class="topbar-left">
            <label class="base-url">
              <span>Base URL</span>
              <input :value="baseUrl" readonly v-tip="'Requests always go to the service that served this page'" />
            </label>
            <label class="accept-field">
              <span>Accept</span>
              <ComboBox v-model="accept" :options="acceptOptions" placeholder="(none — browser default */*)"
                v-tip="'Response format to request. application/json yields readable JSON errors; */* (the browser default) returns the Thrift-encoded TError. Leave blank to send no Accept header.'" />
            </label>
          </div>
          <div class="headers-editor">
            <span class="he-title">Headers</span>
            <div class="he-row" v-for="(h, i) in headers" :key="i">
              <input class="he-key" v-model="h.key" placeholder="Header name" />
              <input class="he-val" v-model="h.value" :placeholder="h.ph || 'value'" v-tip="h.hint || ''" />
              <button class="btn-mini danger" type="button" @click="removeHeader(i)" v-tip="'remove'" aria-label="remove header">✕</button>
            </div>
            <div class="he-actions">
              <button class="btn-mini add" type="button" @click="addHeader">+ Add header</button>
              <select v-if="headerPresets.length" class="he-platform" v-model="headerToAdd" @change="addPreset(headerToAdd)">
                <option value="">+ Add preset header…</option>
                <optgroup v-for="g in headerPresets" :key="g.group" :label="g.group">
                  <option v-for="ph in g.items" :key="ph.name" :value="ph.name">{{ ph.label }} — {{ ph.name }}</option>
                </optgroup>
              </select>
            </div>
            <component v-for="(panel, i) in authPanels" :is="panel" :key="i" />
          </div>
          </div>
        </div>
        </div>

        <div v-if="loading" class="status-msg">Loading spec…</div>
        <div v-else-if="error" class="status-msg error">Failed to load spec: {{ error }}</div>
        <OperationPanel v-else-if="selected" :operation="selected" :base-url="baseUrl" :headers="headers" :accept="accept" />
        <div v-else class="status-msg">Select an operation from the left.</div>
      </main>
    </div>
  `
}
