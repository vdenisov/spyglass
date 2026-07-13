import { ref, reactive, computed, onMounted, onBeforeUnmount, nextTick, watch } from 'vue'
import { CONFIG, storageKey, isSameOriginExtension, resolveUpdateCheckConfig, resolveRequestLogConfig, resolveBrandingConfig, resolveShareLinkConfig } from '../config.js'
import { loadSpec, collectOperations, specRawText, specEtag } from '../spec.js'
import { decodeState, expandSharePayload, STATE_SEP } from '../shareLink.js'
import { loadJson, saveJson, clearSaved, HEADERS_KEY, AUTH_TOKEN_KEY, SIDEBAR_WIDTH_KEY, ACCEPT_KEY } from '../storage.js'
import { getValues, recordValue, removeValue, authKey } from '../history.js'
import { registry, registerAuthPanel, registerHeaderPresets, registerHeaderLinkResolver, registerFooterItem, registerBodyTransformer, loadExtensions } from '../extensions.js'
import { recordExecution, registerSanitizer, configureRequestLog } from '../requestLog.js'
import { useUpdateCheck } from '../useUpdateCheck.js'
import Sidebar from './Sidebar.js'
import OperationPanel from './OperationPanel.js'
import ThemeToggle from './ThemeToggle.js'
import UpdateToast from './UpdateToast.js'
import KeyboardHelp from './KeyboardHelp.js'

const MIN_SIDEBAR = 240

// Root component: loads the spec, owns the base-URL and global headers, and shows the selected operation.
// Consumer-specific UI (an Authorization-header generator, header presets) is contributed by
// front-end extensions through the seam (see extensions.js); the core ships none of it.
export default {
  name: 'App',
  components: { Sidebar, OperationPanel, ThemeToggle, UpdateToast, KeyboardHelp },
  setup() {
    const loading = ref(true)
    const error = ref('')
    const title = ref('API')
    const operations = ref([])
    const selected = ref(null)
    // Requests always go to the origin that served the explorer (same-origin; cross-origin
    // would be blocked by CORS anyway), so the base URL is fixed, not editable.
    const baseUrl = ref(window.location.origin)

    // Per-operation execution state, kept in memory and keyed by op.id. Each slice owns that
    // operation's in-flight AbortController, its `sending` flag, its latest `response`, and a send
    // sequence (latest-wins). Holding this here — not inside the reused OperationPanel — is what lets a
    // request started on one operation keep running, and its response land, while the user views
    // another; returning to the operation shows its in-flight-or-completed state. Responses are
    // deliberately never persisted (they can be large or sensitive — see opForm.js).
    const opStates = reactive({})
    // `showCancel` gates the in-flight Cancel affordance (revealed only after a send stays in flight past
    // a short debounce — see OperationPanel.js) and, like `sending`, lives on the slice so it survives
    // operation switches; `cancelTimer` is the pending debounce timer so it can be cleared.
    const execStateFor = (op) => (opStates[op.id] ||= { sending: false, response: null, inflight: null, seq: 0, showCancel: false, cancelTimer: null })
    // Resolve the current operation's slice off the render path (creating it lazily here, in a watcher,
    // rather than in the template, avoids mutating reactive state during render).
    const currentExec = ref(null)
    watch(selected, (op) => { currentExec.value = op ? execStateFor(op) : null }, { immediate: true })

    // Stable per-row key for the header editor. An index key would let Vue reuse an input's DOM/state
    // for the wrong row after a splice removal (a half-typed value jumping to its neighbour); mirrors
    // spec.js's `_key` rows.
    let headerSeq = 0
    const nextHeaderKey = () => ++headerSeq
    const headerRow = (h) => ({ _key: nextHeaderKey(), key: h.key || '', value: h.value || '', ph: h.ph, hint: h.hint })

    // Hydrate persisted header rows. The explorer doesn't force an Authorization row: a present-but-
    // empty Authorization is malformed and, on this same-origin explorer, can suppress the cookie/
    // session auth the user already has, so a row exists only once the user or an extension adds one.
    // The Authorization value isn't persisted in localStorage (it's a short-lived token in
    // sessionStorage); overlay it back, recreating the row if a token outlived its persisted row.
    const storedHeaders = loadJson(localStorage, HEADERS_KEY, null)
    const initialHeaders = Array.isArray(storedHeaders) ? storedHeaders.map(headerRow) : []
    const storedToken = loadJson(sessionStorage, AUTH_TOKEN_KEY, '') || ''
    const authRow = initialHeaders.find(h => (h.key || '').toLowerCase() === 'authorization')
    if (authRow) authRow.value = storedToken
    else if (storedToken) initialHeaders.unshift(headerRow({ key: 'Authorization', value: storedToken }))
    const headers = ref(initialHeaders)

    // A stored sidebar width wins over the measured default (applied/clamped in onMounted).
    const storedWidth = loadJson(localStorage, SIDEBAR_WIDTH_KEY, null)
    const sidebarWidth = ref(typeof storedWidth === 'number' ? storedWidth : 320)
    // Bumped on "Clear" (exposed as api.headers.resetSignal) so an extension's auth panel resets its
    // own form state.
    const authResetSeq = ref(0)

    // The Accept header (response-format negotiation), applied to every try-it-out request.
    // application/json yields readable JSON errors; */* accepts any type. `savedAccept` is the user's
    // persisted global preference; `accept` is what the current operation actually sends and the
    // ComboBox shows — it equals the preference unless the operation can't satisfy it (see below).
    const savedAccept = ref(loadJson(localStorage, ACCEPT_KEY, 'application/json'))
    const accept = ref(savedAccept.value)
    // A ComboBox edit is a deliberate user choice: it updates the saved preference (and persists). The
    // per-operation auto-narrow below writes only `accept`, so it never overwrites the preference.
    const onAcceptInput = (v) => { accept.value = v; savedAccept.value = v; saveJson(localStorage, ACCEPT_KEY, v) }
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
    // On each operation, start from the saved preference, then narrow only when this operation can't
    // satisfy it — e.g. an image/binary download would 406 with application/json. This is a
    // per-operation override (written to `accept`, not `savedAccept`), so a previous operation's
    // narrowing never sticks and the user's global preference is preserved untouched.
    // immediate, so a deep-linked/first-selected operation that can't satisfy the saved Accept (e.g. an
    // image-only download vs application/json) is narrowed on load too, not only on a later switch.
    watch(selected, (op) => {
      const produced = successProduced(op)
      accept.value = (produced.length && !acceptSatisfiable(savedAccept.value, produced)) ? produced[0] : savedAccept.value
    }, { immediate: true })
    const addHeader = () => headers.value.push(headerRow({ key: '', value: '' }))
    const removeHeader = (i) => headers.value.splice(i, 1)

    // Header presets: an extension may contribute named header groups (see extensions.js); selecting
    // one adds a row with the correct wire name and any format hint pre-filled.
    const headerToAdd = ref('')
    const addPreset = (name) => {
      for (const group of registry.headerPresets) {
        const def = group.items.find(i => i.name === name)
        if (def) { headers.value.push(headerRow({ key: def.name, value: '', ph: def.ph, hint: def.hint })); break }
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
      else headers.value.unshift(headerRow({ key: 'Authorization', value }))
    }

    // Update check (see useUpdateCheck.js). Created here in setup() so its onBeforeUnmount cleanup
    // registers while the instance is active; actually started below in onMounted, once the spec has
    // loaded (so the baseline hash is taken from the document this tab rendered).
    const updateCheck = useUpdateCheck()

    // The UI-relevant Request Log config (enabled gate + display fold), resolved from the spec layer in
    // onMounted and handed to the OperationPanel. The write-path config (caps, body cap) is applied to
    // the capture modules separately via configureRequestLog. Defaults match the config defaults so the
    // panel behaves correctly in the brief window before the spec loads (no operation renders then).
    const requestLogUi = ref({ enabled: true, foldN: 5 })

    // Whether the sidebar footer shows Spyglass's own mark; resolved from the branding config (spec
    // layer folded in) in onMounted. Defaults to true so the mark shows in the brief window before
    // the spec loads, matching the config default. A white-label host turns it off via the standard
    // config chain (see resolveBrandingConfig); extension-contributed footer items show regardless.
    const brandingShow = ref(true)

    // Max length of a generated share link, resolved from the config seam in onMounted and handed to the
    // OperationPanel's "Copy link". Default matches the config default for the pre-spec window.
    const shareLinkMaxUrl = ref(4000)
    // A pending shared deep-link to rehydrate into the current operation's form ({ opId, snap, seq }),
    // set by applyDeepLinkState when a link is opened and consumed by the OperationPanel (once per seq).
    const pendingDeepLink = ref(null)
    let deepLinkSeq = 0

    // The seam context handed to each extension's register(api). It exposes the loaded spec (for the
    // extension to read its own x-* info extensions), the header bridge (add rows, read/set the
    // Authorization value, observe Clear-all), persistence/history helpers (so extensions don't import
    // core modules by path), and the UI registration hooks.
    const buildExtensionApi = (spec) => ({
      spec,
      config: CONFIG,
      headers: {
        add: (row) => headers.value.push(headerRow(row)),
        authorization: authorizationValue,
        setAuthorization,
        resetSignal: authResetSeq
      },
      storage: { key: storageKey, load: loadJson, save: saveJson },
      history: { values: getValues, record: recordValue, remove: removeValue, key: authKey },
      ui: { registerAuthPanel, registerHeaderPresets, registerHeaderLinkResolver, registerFooterItem },
      // An embedding service decodes a machine-oriented JSON response into a more readable view
      // behind the response panel's Decoded toggle. A transformer reads its own x-* extensions off
      // ctx.operation / api.spec (exposed above); JSON-only, transform-on-display, raw stays one
      // click away. See extensions.js for the chaining/failure-isolation contract.
      response: { registerBodyTransformer },
      // An embedding service redacts org-specific surfaces of a Request Log record (request/response
      // headers and bodies, the query in the URL and the replay snapshot) before it is persisted. The
      // sanitizer runs after the core Authorization default and in registration order; throwing drops
      // the record (fail-closed) rather than storing it un-redacted.
      requestLog: { registerSanitizer }
    })

    // Start the update check: poll the spec for a sustained content change and raise the reload toast.
    // The baseline is the spec text loadSpec already captured, so it's tied to the document this tab
    // rendered; config is resolved with the spec layer folded in (x-spyglass-config).
    const startUpdateCheck = (spec) => {
      const config = resolveUpdateCheckConfig(spec)
      if (!config.enabled) return
      updateCheck.start({ config, specUrl: CONFIG.specUrl, loadedText: specRawText(), loadedEtag: specEtag() })
    }

    // The URL hash is the source of truth for the current selection: #<METHOD>-<path>. A shared
    // deep-link (#24) appends the encoded request state after the anchor as "&s=<blob>" (STATE_SEP);
    // that part is split off here and rehydrated separately, leaving the anchor parse unchanged.
    const anchorFor = (op) => `${op.method}-${op.path}`
    const select = (op) => { window.location.hash = anchorFor(op) }
    const applyHash = () => {
      const raw = window.location.hash.replace(/^#/, '')
      if (!raw) { selected.value = null; return }
      let decoded
      try { decoded = decodeURIComponent(raw) } catch (e) { decoded = raw }
      const sepAt = decoded.indexOf(STATE_SEP)
      const anchor = sepAt < 0 ? decoded : decoded.slice(0, sepAt)
      const state = sepAt < 0 ? '' : decoded.slice(sepAt + STATE_SEP.length)
      const i = anchor.indexOf('-')
      const op = i < 0 ? null : operations.value.find(o => o.method === anchor.slice(0, i) && o.path === anchor.slice(i + 1))
      if (op) {
        selected.value = op
        // The panel mounts from the anchor immediately; the encoded state (if any) is decoded off the
        // render path and applied once it resolves — see applyDeepLinkState.
        if (state) applyDeepLinkState(op, state)
        nextTick(() => { const el = document.querySelector('.op-link.active'); if (el) el.scrollIntoView({ block: 'nearest' }) })
      } else {
        // Invalid anchor: render nothing and erase it.
        selected.value = null
        history.replaceState(null, '', window.location.pathname + window.location.search)
      }
    }

    // Rehydrate a shared deep-link (the "&s=<blob>" fragment) into the just-selected operation: decode
    // it, replace the global header set with the link's non-secret headers (preserving the recipient's
    // own Authorization row, which the link never carries), and hand the form snapshot to the panel via
    // pendingDeepLink (it rides the Request Log replay path — best-effort against the current schema).
    // The consumed state is then stripped from the URL so a reload or later hashchange doesn't re-apply
    // it over the recipient's edits. A malformed/truncated blob decodes to null and is ignored; the
    // operation still opens from its anchor.
    const applyDeepLinkState = async (op, encoded) => {
      const payload = expandSharePayload(await decodeState(encoded))
      if (payload) {
        // Replace the header set with the link's, but keep the recipient's own Authorization row — the
        // link never carried it, so replacing it would only blank their token.
        const authRow = headers.value.find(h => (h.key || '').toLowerCase() === 'authorization')
        const rows = payload.headers
          .filter(h => h && h.key && (h.key || '').toLowerCase() !== 'authorization')
          .map(h => headerRow({ key: h.key, value: h.value }))
        if (authRow) rows.unshift(authRow)
        headers.value = rows
        pendingDeepLink.value = { opId: op.id, snap: payload.snap, seq: ++deepLinkSeq }
      }
      // Strip the consumed state from the address bar (leave the anchor), so a reload or later
      // hashchange doesn't re-apply it. Best-effort — a rejected replaceState must not break rehydration.
      try { history.replaceState(null, '', window.location.pathname + window.location.search + '#' + anchorFor(op)) } catch (e) { /* leave the URL as-is */ }
    }

    // The sidebar may not exceed half the viewport, and never shrinks below MIN_SIDEBAR.
    const clampWidth = (px) => Math.min(Math.max(px, MIN_SIDEBAR), Math.floor(window.innerWidth * 0.5))

    // Default width = the narrowest width at which the operation list shows no horizontal scrollbar
    // (so paths aren't clipped), capped at 50%. Two steps, because a row is a full-width flex button
    // whose own scrollWidth can't reveal how much wider its content wants to be:
    //   (1) an ideal lower bound — with the sidebar temporarily widened off-screen, the widest row's
    //       content extent (its left edge to the right edge of its widest child);
    //   (2) grow from there until the list stops overflowing horizontally (scrollWidth <= clientWidth).
    //       Measured on the real constrained layout, step 2 absorbs the paddings, the vertical
    //       scrollbar's width, and the sub-pixel accumulation of glyph positions at a fractional
    //       devicePixelRatio (a HiDPI / OS-scaled display) — none of which step 1 can see.
    // All synchronous: each width write forces the reflow the next read observes, so nothing paints
    // until the final reactive width is committed.
    const measureSidebar = () => {
      const el = document.querySelector('.sidebar')
      const list = el && el.querySelector('.op-list')
      if (!el || !list) return
      const savedWidth = el.style.width
      const savedFlex = el.style.flex
      const setW = (px) => { el.style.flex = '0 0 ' + px + 'px'; el.style.width = px + 'px' }

      setW(10000)
      const sidebarLeft = el.getBoundingClientRect().left
      let maxRight = 0
      document.querySelectorAll('.op-link').forEach(l => {
        for (const c of l.children) { const r = c.getBoundingClientRect().right; if (r > maxRight) maxRight = r }
      })
      if (maxRight <= sidebarLeft) { el.style.width = savedWidth; el.style.flex = savedFlex; return }

      let target = clampWidth(Math.ceil(maxRight - sidebarLeft) + 8)
      const maxTarget = clampWidth(Number.MAX_SAFE_INTEGER)
      for (let i = 0; i < 6 && target < maxTarget; i++) {
        setW(target)
        const over = list.scrollWidth - list.clientWidth
        if (over <= 0) break
        target = clampWidth(Math.ceil(target + over + 1))
      }

      el.style.width = savedWidth
      el.style.flex = savedFlex
      sidebarWidth.value = target
    }

    // Fit-to-content on demand — the same measurement as the initial default width, re-run when the
    // user double-clicks the divider or presses "f" while it's focused (a spreadsheet-style auto-size).
    // Like the drag it's clamped to [MIN_SIDEBAR, 50% of the viewport], so a path wider than the cap
    // settles at the cap rather than expanding without bound.
    const fitSidebar = () => measureSidebar()

    // Re-clamp the sidebar against the new viewport (the cap is half the width). Named so it — and the
    // hashchange handler — can be removed on unmount rather than leaking past the component's life.
    const onResize = () => { sidebarWidth.value = clampWidth(sidebarWidth.value) }

    // Keyboard resize for the focusable divider (role=separator): arrows nudge (Shift = coarser),
    // Home/End jump to the min/max, "f" fits to the widest row. Mirrors the drag, clamped the same way.
    const onDividerKey = (e) => {
      const step = e.shiftKey ? 40 : 16
      if (e.key === 'ArrowLeft') { e.preventDefault(); sidebarWidth.value = clampWidth(sidebarWidth.value - step) }
      else if (e.key === 'ArrowRight') { e.preventDefault(); sidebarWidth.value = clampWidth(sidebarWidth.value + step) }
      else if (e.key === 'Home') { e.preventDefault(); sidebarWidth.value = clampWidth(MIN_SIDEBAR) }
      else if (e.key === 'End') { e.preventDefault(); sidebarWidth.value = clampWidth(window.innerWidth) }
      // Plain "f" only — don't hijack Ctrl/Cmd+F (browser find) or other modified combos.
      else if (e.key.toLowerCase() === 'f' && !e.ctrlKey && !e.metaKey && !e.altKey) { e.preventDefault(); fitSidebar() }
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

    // Keyboard-shortcuts help overlay (KeyboardHelp.js). App owns the open state and the "?" hotkey
    // (a feature-specific keybinding); the dialog's own Escape-to-close, focus trap and focus restore
    // live in the reusable Modal. "?" toggles it from anywhere except while typing in a field — same
    // editable-target guard as the sidebar's "/" (Sidebar.js). "?" is Shift+/, so it never collides
    // with the "/" shortcut (that handler only fires on e.key === '/').
    const helpShow = ref(false)
    const toggleHelp = () => { helpShow.value = !helpShow.value }
    const closeHelp = () => { helpShow.value = false }
    const onHelpKey = (e) => {
      if (e.key !== '?' || e.ctrlKey || e.metaKey || e.altKey) return
      const t = e.target
      const tag = (t && t.tagName || '').toUpperCase()
      if (tag === 'INPUT' || tag === 'TEXTAREA' || tag === 'SELECT' || (t && t.isContentEditable)) return
      e.preventDefault()
      toggleHelp()
    }

    onMounted(async () => {
      try {
        const spec = await loadSpec(CONFIG.specUrl)
        if (spec.info && spec.info.title) { title.value = spec.info.title; document.title = spec.info.title }
        operations.value = collectOperations()
        // Request Log config (config.js folds in the spec's x-spyglass-config.requestLog layer): apply
        // the write-path settings (enable gate, caps, body cap) to the capture modules, and keep the
        // UI-relevant slice for the panel. Resolved before applyHash, so it's set before any panel renders.
        const requestLogConfig = resolveRequestLogConfig(spec)
        configureRequestLog(requestLogConfig)
        requestLogUi.value = { enabled: requestLogConfig.enabled, foldN: requestLogConfig.foldN }
        // Branding (config.js folds in the spec's x-spyglass-config.branding layer): whether the
        // built-in Spyglass footer mark renders. Resolved before applyHash, like the request log.
        brandingShow.value = resolveBrandingConfig(spec).show
        // Share-link size cap (config.js folds in the spec's x-spyglass-config.shareLink layer), handed
        // to the OperationPanel's Copy link. Resolved before applyHash, like the request log / branding.
        shareLinkMaxUrl.value = resolveShareLinkConfig(spec).maxUrl
        // Front-end extensions: the operator's query/global list (resolved in config.js) is trusted and
        // wins. Otherwise the spec may advertise modules via the x-spyglass-extensions info extension —
        // but a spec is less trusted, so those are limited to same-origin (a cross-origin URL there
        // could load arbitrary third-party ESM into this origin). The effective list is local; CONFIG
        // is not mutated. Each module's register(api) contributes UI through the seam.
        const specExtensions = (spec.info && spec.info['x-spyglass-extensions']) || []
        const extensions = CONFIG.extensions.length
          ? CONFIG.extensions
          : (Array.isArray(specExtensions) ? specExtensions.filter(isSameOriginExtension) : [])
        await loadExtensions(buildExtensionApi(spec), extensions)
        await nextTick()
        // A persisted width wins; otherwise fall back to the measured (widest-row) default.
        if (storedWidth == null) measureSidebar()
        else sidebarWidth.value = clampWidth(sidebarWidth.value)
        applyHash()
        startUpdateCheck(spec)
      } catch (e) {
        error.value = e.message
      } finally {
        loading.value = false
      }
      window.addEventListener('hashchange', applyHash)
      window.addEventListener('resize', onResize)
      document.addEventListener('keydown', onHelpKey)
    })
    onBeforeUnmount(() => {
      window.removeEventListener('hashchange', applyHash)
      window.removeEventListener('resize', onResize)
      document.removeEventListener('keydown', onHelpKey)
    })

    // Persist request state on change. Authorization value → sessionStorage (short-lived token);
    // the remaining header rows → localStorage. Sidebar width → localStorage.
    watch(headers, () => {
      // Persist the wire fields only (not the ephemeral `_key`); blank the Authorization value (its
      // token lives in sessionStorage, restored on hydrate).
      const rows = headers.value.map(h => ({
        key: h.key, value: (h.key || '').toLowerCase() === 'authorization' ? '' : h.value, ph: h.ph, hint: h.hint
      }))
      saveJson(localStorage, HEADERS_KEY, rows)
      saveJson(sessionStorage, AUTH_TOKEN_KEY, authorizationValue.value)
    }, { deep: true })
    watch(sidebarWidth, (w) => saveJson(localStorage, SIDEBAR_WIDTH_KEY, w))

    // "Clear headers": reset the *shared* request inputs — the header rows (cleared) and Accept — wipe
    // their persisted copies, and bump the reset signal so any extension panel (e.g. an auth generator)
    // clears its own state too. Per-operation inputs (each operation's own Reset handles those), field
    // history, the sidebar width (layout) and UI preferences (theme, response-pretty) are kept.
    const clearHeaders = () => {
      clearSaved()
      headers.value = []
      savedAccept.value = 'application/json'
      accept.value = 'application/json'
      authResetSeq.value++
    }

    return {
      loading, error, title, operations, selected, baseUrl, headers, sidebarWidth,
      authorizationValue, setAuthorization, authResetSeq,
      accept, acceptOptions, onAcceptInput,
      addHeader, removeHeader, select, startDrag, onDividerKey, fitSidebar, minSidebar: MIN_SIDEBAR, clearHeaders,
      currentExec, recordExecution, requestLogUi, brandingShow, shareLinkMaxUrl, pendingDeepLink,
      headerPresets: registry.headerPresets, authPanels: registry.authPanels, headerToAdd, addPreset,
      updateToastShow: updateCheck.show, onUpdateReload: updateCheck.reload, onUpdateDismiss: updateCheck.dismiss,
      helpShow, toggleHelp, closeHelp
    }
  },
  template: `
    <div class="layout">
      <Sidebar :operations="operations" :selected-id="selected ? selected.id : ''" :title="title" :loading="loading" :branding-show="brandingShow" @select="select"
        :style="{ flex: '0 0 ' + sidebarWidth + 'px', width: sidebarWidth + 'px' }" />
      <div class="divider" role="separator" aria-orientation="vertical" aria-label="Resize sidebar"
        :aria-valuenow="Math.round(sidebarWidth)" :aria-valuemin="minSidebar" tabindex="0"
        @mousedown.prevent="startDrag" @dblclick.prevent="fitSidebar" @keydown="onDividerKey"
        v-tip.cursor="'Drag to resize; double-click to fit. When focused: ←/→ resize, Home/End min/max, f to fit.'"></div>
      <main class="main">
        <div class="topbar-wrap">
        <div class="topbar">
          <div class="topbar-actions">
            <button class="btn-mini danger btn-clear-headers" type="button" @click="clearHeaders"
              v-tip="'Clears the shared request settings — the global headers, Accept, and any extension panels. Per-operation inputs (path, query, body) are kept — use an operation\\'s Reset to clear it. Field history, layout and theme are kept.'">Clear headers</button>
            <ThemeToggle />
            <button class="btn-help" type="button" @click="toggleHelp" aria-label="Keyboard shortcuts"
              aria-haspopup="dialog" :aria-expanded="helpShow ? 'true' : 'false'" v-tip="'Keyboard shortcuts (?)'"><span aria-hidden="true">?</span></button>
          </div>
          <div class="topbar-main">
          <div class="topbar-left">
            <label class="base-url">
              <span>Base URL</span>
              <input :value="baseUrl" readonly v-tip="'Requests always go to the service that served this page'" />
            </label>
            <label class="accept-field">
              <span>Accept</span>
              <ComboBox :model-value="accept" @update:model-value="onAcceptInput" :options="acceptOptions" placeholder="(none — browser default */*)"
                v-tip="'Response format to request. application/json yields readable JSON errors; */* accepts any type. Leave blank to send no Accept header.'" />
            </label>
          </div>
          <div class="headers-editor">
            <span class="he-title">Headers</span>
            <div class="he-row" v-for="(h, i) in headers" :key="h._key">
              <input class="he-key" v-model="h.key" aria-label="Header name" placeholder="Header name" />
              <input class="he-val" v-model="h.value" aria-label="Header value" :placeholder="h.ph || 'value'" v-tip="h.hint || ''" />
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

        <div v-if="loading" class="status-msg" role="status">Loading spec…</div>
        <div v-else-if="error" class="status-msg error" role="alert">Failed to load spec: {{ error }}</div>
        <OperationPanel v-else-if="selected" :operation="selected" :exec-state="currentExec" :base-url="baseUrl" :headers="headers" :accept="accept" :on-executed="recordExecution"
          :request-log-enabled="requestLogUi.enabled" :request-log-fold-n="requestLogUi.foldN"
          :share-link-max-url="shareLinkMaxUrl" :pending-deep-link="pendingDeepLink" />
        <div v-else class="status-msg" role="status">Select an operation from the left.</div>
      </main>
      <UpdateToast :show="updateToastShow" :title="title" @reload="onUpdateReload" @dismiss="onUpdateDismiss" />
      <KeyboardHelp :show="helpShow" @close="closeHelp" />
    </div>
  `
}
