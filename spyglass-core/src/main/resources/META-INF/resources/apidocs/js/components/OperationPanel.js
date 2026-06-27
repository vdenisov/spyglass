import { ref, computed, watch, nextTick, defineAsyncComponent, onMounted, onBeforeUnmount } from 'vue'
import { makeNode, serializeNode, importValue, requestBodyMediaTypes, serializeMultipart, serializeUrlEncoded, getResponseSchemas, schemaTree, schemaExample, namedExamples, exampleToField, specRoot, collectBodyWarnings, OMIT } from '../spec.js'
import { mdBlock } from '../markdown.js'
import { getValues, recordValue, removeValue, paramKey, bodyFieldKey } from '../history.js'
import { loadForm, saveForm, removeForm } from '../opForm.js'
import { copyText } from '../clipboard.js'
import { statusKind } from '../format.js'
import { useFlash } from '../useFlash.js'
import ParamInputs from './ParamInputs.js'
import ResponseView from './ResponseView.js'

// Loaded only when the Raw JSON tab is first opened, so the CodeMirror bundle stays out of the
// explorer's initial payload.
const JsonEditor = defineAsyncComponent(() => import('./JsonEditor.js'))

const BODY_METHODS = new Set(['POST', 'PUT', 'PATCH', 'DELETE'])

// How long a send must stay in flight before the Cancel control (and the greyed "Sending…" Send) is
// revealed. Below this the request almost always completes, so a fast request shows no flashed Cancel
// and no Send→grey morph; it is also short enough that a user can't realistically click Cancel within
// it (well under human move-and-click reaction time), so nothing cancellable is hidden in practice.
const CANCEL_DEBOUNCE_MS = 250

function controlFor(schema) {
  if (!schema) return { control: 'text', placeholder: 'string' }
  if (Array.isArray(schema.enum)) return { control: 'enum', options: schema.enum }
  const t = Array.isArray(schema.type) ? schema.type.filter(x => x !== 'null')[0] : schema.type
  if (t === 'boolean') return { control: 'boolean' }
  if (t === 'array') {
    const items = schema.items || {}
    const itemKind = Array.isArray(items.type) ? items.type.filter(x => x !== 'null')[0] : items.type
    return { control: 'array', itemEnum: Array.isArray(items.enum) ? items.enum : null, itemKind: itemKind || null }
  }
  return { control: 'text', placeholder: t || 'string' }
}

function splitLines(text) {
  return (text || '').split('\n').map(s => s.trim()).filter(s => s !== '')
}

// Whether a response body should be decoded to text (JSON/text-like, or unknown so we can sniff it).
// Binary types are kept as bytes so they aren't corrupted by UTF-8 decoding.
function isTextLike(ct) {
  const c = (ct || '').toLowerCase()
  if (!c) return true
  return c.includes('json') || c.startsWith('text/') || c.includes('xml') || c.includes('html') || c.includes('javascript') || c.includes('csv')
}

// Drives a single operation: parameters, request body (form or raw JSON), execution and response.
export default {
  name: 'OperationPanel',
  components: { ParamInputs, ResponseView, JsonEditor },
  props: {
    operation: { type: Object, required: true },
    // Per-operation execution state, owned by App and keyed by op.id ({ sending, response, inflight,
    // seq }). The panel is a single reused instance; routing execution through this injected slice —
    // not local refs — is what keeps a request alive (and its response addressable) across operation
    // switches.
    execState: { type: Object, required: true },
    baseUrl: { type: String, default: '' },
    headers: { type: Array, default: () => [] },
    accept: { type: String, default: '' },
    // Optional hook called once a send settles successfully ({ opId, req, response }); the seam a future
    // request-history store hangs off without touching send().
    onExecuted: { type: Function, default: null }
  },
  setup(props) {
    const paramState = ref([])
    const bodyTypes = ref([])
    const mediaType = ref('')
    const bodyNode = ref(null)
    const bodySupported = ref(false)
    const useRaw = ref(false)
    const rawText = ref('')
    const rawError = ref('')
    // `response` and `sending` live on the per-operation execState slice (so they survive switches);
    // expose read-only views so the template and shortcut handler are unchanged.
    const response = computed(() => props.execState.response)
    const sending = computed(() => props.execState.sending)
    // True once a send has been in flight past CANCEL_DEBOUNCE_MS (and still is): gates the Cancel
    // control and the greyed "Sending…" Send. Lives on the execState slice (like `sending`) so it
    // survives operation switches. See armCancelTimer and the send-bar template below.
    const showCancel = computed(() => props.execState.showCancel)
    const { flag: copied, flash: flashCopied } = useFlash()
    // A separate flash for the header's Operation ID copy, so its "Copied" cue is local and doesn't
    // also trigger the send-bar's shared copy note.
    const { flag: opIdCopied, flash: flashOpId } = useFlash()
    const tab = ref('try')
    const schemaView = ref('schema')
    // True while rebuild() is reseeding the form from a saved snapshot — suppresses the persist watcher
    // so seeding doesn't immediately re-save (and so a Reset isn't undone). Reset before the watcher's
    // post-flush run (see nextTick in rebuild). `saveTimer` debounces same-operation persistence.
    let seeding = false
    let saveTimer = null

    // Documentation (read-only) derived from the spec for the Schema tab.
    // The selected request-body media type and its editor/serialization strategy.
    const bodyMt = computed(() => bodyTypes.value.find(t => t.mediaType === mediaType.value) || null)
    const curKind = computed(() => (bodyMt.value ? bodyMt.value.kind : null))

    const requestRef = computed(() => {
      const mt = bodyMt.value
      if (!mt) return null
      return { tree: schemaTree(mt.schema) }
    })
    const responseRefs = computed(() => getResponseSchemas(props.operation).map(r => ({
      status: r.status,
      description: r.description,
      schema: r.schema,
      headers: r.headers,
      examples: r.examples || [],
      tree: r.schema ? schemaTree(r.schema) : null
    })))
    const statusClass = (status) => statusKind(status)

    // --- Schema → Examples gallery -------------------------------------------
    //
    // Every spec example (named + singular) rendered one per card. When a body/response declares no
    // examples we fall back to a single instance generated from the schema. Request-body example
    // cards can prefill the Raw JSON editor; parameter/response cards are documentation only.
    const generatedCard = (schema) => {
      const g = schemaExample(schema)
      return (g === null || g === undefined) ? null : { name: 'Generated', summary: 'Generated from the schema', description: '', value: g, externalValue: '' }
    }
    const requestExamples = computed(() => {
      const mt = bodyMt.value
      if (!mt) return []
      if (mt.examples.length) return mt.examples
      const gen = generatedCard(mt.schema)
      return gen ? [gen] : []
    })
    // The request body can be prefilled only when it has a raw editor (JSON) or raw textarea (other).
    const reqCanPrefill = computed(() => curKind.value === 'json' || curKind.value === 'other')
    const responseExamples = (r) => r.examples.length ? r.examples : (r.schema ? [generatedCard(r.schema)].filter(Boolean) : [])
    // Parameter examples (named + singular), grouped by parameter — only those that declare any.
    const paramExampleGroups = computed(() =>
      props.operation.parameters
        .map(p => ({ name: p.name, in: p.in, examples: namedExamples(p) }))
        .filter(g => g.examples.length))
    const hasAnyExamples = computed(() =>
      requestExamples.value.length || paramExampleGroups.value.length ||
      responseRefs.value.some(r => responseExamples(r).length))

    // Prefill the Raw JSON editor (or raw textarea for non-JSON) with an example value, then surface it.
    // The body is usually the last thing a user fills, so jumping to Try-it-out here is the right move.
    const prefillRaw = (value) => {
      const mt = bodyMt.value
      if (!mt) return
      tab.value = 'try'
      if (mt.kind === 'other') rawText.value = typeof value === 'string' ? value : pretty(value)
      else { useRaw.value = true; rawText.value = pretty(value) }
    }
    // Fill a single parameter input from one of its examples. Unlike the body, this does NOT jump to
    // Try-it-out (an operation can have many params; the card's brief "Applied" flash is the feedback).
    const prefillParam = (group, value) => {
      const p = paramState.value.find(x => x.name === group.name && x.in === group.in)
      if (p) p.value = exampleToField(value)
    }

    // Schema handed to the raw-JSON editor for validation and autocomplete. The body
    // schema's internal `#/components/...` $refs resolve against the whole OpenAPI
    // document, so hoist that namespace onto the schema we pass down.
    const editorSchema = computed(() => {
      const mt = bodyMt.value
      if (!mt || mt.kind !== 'json') return null
      const root = specRoot() || {}
      return { ...mt.schema, components: root.components, $defs: root.$defs }
    })

    const currentPayload = () => {
      if (!bodyNode.value) return {}
      const v = serializeNode(bodyNode.value)
      return v === OMIT ? {} : v
    }
    const pretty = (value) => JSON.stringify(value, null, 2)

    // (Re)builds the body editor for the currently-selected media type. Called on operation change and
    // when the user switches the media-type selector.
    const rebuildBody = () => {
      const mt = bodyMt.value
      rawError.value = ''
      if (!mt) { bodyNode.value = null; bodySupported.value = false; useRaw.value = false; rawText.value = ''; return }
      if (mt.kind === 'other') {
        // Arbitrary content types have no schema form — a plain raw text editor.
        bodyNode.value = null; bodySupported.value = false; useRaw.value = true; rawText.value = ''
        return
      }
      bodyNode.value = makeNode(mt.schema, true)
      bodySupported.value = bodyNode.value.kind !== 'unsupported'
      // Only JSON offers a Raw editor fallback; urlencoded/multipart are form-only.
      useRaw.value = mt.kind === 'json' ? !bodySupported.value : false
      rawText.value = mt.kind === 'json' ? pretty(currentPayload()) : ''
    }

    // Apply a saved form snapshot onto the freshly-rebuilt tree. JSON bodies hydrate via importValue
    // (the same translation the Form/Raw toggle uses), so nested objects/arrays/maps and oneOf/anyOf
    // selections restore to whatever fidelity that path supports; a raw-mode body also restores its
    // verbatim text and mode. Files (multipart) aren't JSON-serializable, so file inputs stay empty.
    const seedBody = (snap) => {
      const mt = bodyMt.value
      if (!mt) return
      if (mt.kind === 'other') {
        if (typeof snap.rawText === 'string') rawText.value = snap.rawText
        return
      }
      if (snap.body !== undefined && bodyNode.value) {
        importValue(bodyNode.value, snap.body)
        if (mt.kind === 'json') rawText.value = pretty(currentPayload())
      }
      if (mt.kind === 'json' && snap.useRaw) {
        useRaw.value = true
        if (typeof snap.rawText === 'string') rawText.value = snap.rawText
      }
    }

    // Rebuild the panel for the current operation. By default seeds the form from the saved snapshot
    // (opForm); pass { fresh: true } (the Reset button) to start from schema defaults instead. Execution
    // state (response/sending) is NOT touched here — it lives on the execState slice and must survive.
    const rebuild = ({ fresh = false } = {}) => {
      const op = props.operation
      const snap = fresh ? null : loadForm(op.id)
      seeding = true
      paramState.value = op.parameters.map(p => {
        const c = controlFor(p.schema)
        const s = p.schema || {}
        // Only `default` prefills the value; a singular `example` is surfaced as a placeholder hint
        // (the parameter's named examples, if any, are documented in the Schema → Examples tab).
        const def = s.default
        return {
          name: p.name, in: p.in, required: !!p.required, schema: p.schema || null,
          description: p.description || '', deprecated: !!p.deprecated,
          histId: 'phist-' + p.in + '-' + p.name, ...c,
          example: p.example !== undefined ? p.example : s.example, value: def != null ? String(def) : ''
        }
      })
      if (snap && snap.params) {
        for (const p of paramState.value) {
          const k = p.in + ':' + p.name
          if (k in snap.params) p.value = snap.params[k]
        }
      }
      bodyTypes.value = requestBodyMediaTypes(op)
      const defaultMt = bodyTypes.value.length ? bodyTypes.value[0].mediaType : ''
      mediaType.value = (snap && snap.mediaType && bodyTypes.value.some(t => t.mediaType === snap.mediaType)) ? snap.mediaType : defaultMt
      rebuildBody()
      if (snap) seedBody(snap)
      tab.value = 'try'
      // Release the persist guard only after the watcher's post-mutation flush, so this reseed isn't
      // itself persisted (which would otherwise undo a Reset).
      nextTick(() => { seeding = false })
    }

    // Build the current-form snapshot (params + media type + Form/Raw mode + raw text + body as JSON).
    const buildSnapshot = () => {
      const params = {}
      for (const p of paramState.value) params[p.in + ':' + p.name] = p.value
      const mt = bodyMt.value
      const snap = { params, mediaType: mediaType.value, useRaw: useRaw.value, rawText: rawText.value }
      if (mt && mt.kind === 'json') {
        if (useRaw.value) { try { snap.body = JSON.parse(rawText.value || '{}') } catch (e) { /* invalid in-progress JSON — keep rawText only */ } }
        else snap.body = currentPayload()
      } else if (mt && (mt.kind === 'form' || mt.kind === 'multipart')) {
        const v = serializeNode(bodyNode.value)
        snap.body = v === OMIT ? {} : v
      }
      return snap
    }
    // Persist an operation's current form immediately (used when switching away, before rebuild reseeds
    // over it, and on unmount).
    const flushSave = (op) => {
      if (!op || seeding) return
      clearTimeout(saveTimer); saveTimer = null
      saveForm(op.id, buildSnapshot())
    }

    // On operation switch: persist the OUTGOING operation's form (the refs still hold its values here,
    // before rebuild), then rebuild (which reseeds for the incoming operation).
    watch(() => props.operation, (op, prevOp) => { flushSave(prevOp); rebuild() }, { immediate: true })

    // Debounced persistence of same-operation edits, so a reload mid-editing keeps them. Skipped while
    // seeding; the captured opId guards against a switch landing the save under the wrong operation.
    // `deep: true` re-traverses the body node tree on each edit to observe nested field changes. This
    // cost is intentionally accepted: it is sub-millisecond for any form a human actually fills, and the
    // only cheaper alternative (persist on `beforeunload` instead of per-edit) would trade crash-safety
    // for a win that doesn't matter here — nobody hand-edits a thousand-field body in the visual form.
    watch([paramState, mediaType, useRaw, rawText, bodyNode], () => {
      if (seeding) return
      const opId = props.operation.id
      clearTimeout(saveTimer)
      saveTimer = setTimeout(() => { if (props.operation.id === opId) saveForm(opId, buildSnapshot()) }, 300)
    }, { deep: true })

    const setRaw = (raw) => {
      if (raw) {
        rawText.value = pretty(currentPayload())
        rawError.value = ''
        useRaw.value = true
        return
      }
      // Switching back to the form: import the edited JSON into the node tree.
      try {
        const parsed = JSON.parse(rawText.value || '{}')
        importValue(bodyNode.value, parsed)
        rawError.value = ''
        useRaw.value = false
      } catch (e) {
        rawError.value = 'Invalid JSON — fix it before switching to the form: ' + e.message
      }
    }

    const prettyRaw = () => {
      try {
        rawText.value = pretty(JSON.parse(rawText.value || '{}'))
        rawError.value = ''
      } catch (e) {
        rawError.value = 'Invalid JSON: ' + e.message
      }
    }

    const buildRequest = () => {
      const op = props.operation
      let path = op.path
      const qs = new URLSearchParams()
      const headers = {}
      for (const p of paramState.value) {
        const v = p.value
        const lines = p.control === 'array' ? splitLines(v) : null
        if (p.in === 'path') {
          const val = lines ? lines.join(',') : (v || '')
          path = path.split('{' + p.name + '}').join(encodeURIComponent(val))
        } else if (p.in === 'query') {
          if (lines) for (const line of lines) qs.append(p.name, line)
          else if (v !== '' && v != null) qs.append(p.name, v)
        } else if (p.in === 'header') {
          if (lines) { if (lines.length) headers[p.name] = lines.join(',') }
          else if (v !== '' && v != null) headers[p.name] = v
        }
      }
      for (const h of props.headers) { if (h.key) headers[h.key] = h.value }
      // Apply the global Accept (response negotiation) unless an explicit Accept header row overrides it.
      if (props.accept && !Object.keys(headers).some(k => k.toLowerCase() === 'accept')) headers['Accept'] = props.accept

      let bodyStr = null
      let bodyData = null
      const mt = bodyMt.value
      if (mt && BODY_METHODS.has(op.method)) {
        if (mt.kind === 'multipart') {
          // FormData: the browser sets Content-Type (multipart/form-data) with the boundary itself.
          bodyData = serializeMultipart(bodyNode.value)
        } else if (mt.kind === 'form') {
          bodyStr = serializeUrlEncoded(bodyNode.value).toString()
          headers['Content-Type'] = headers['Content-Type'] || 'application/x-www-form-urlencoded'
        } else if (mt.kind === 'other') {
          bodyStr = rawText.value || ''
          headers['Content-Type'] = headers['Content-Type'] || mt.mediaType
        } else {
          bodyStr = useRaw.value ? JSON.stringify(JSON.parse(rawText.value || '{}')) : JSON.stringify(currentPayload())
          headers['Content-Type'] = headers['Content-Type'] || 'application/json'
        }
      }

      const q = qs.toString()
      const relUrl = (props.baseUrl || '') + path + (q ? '?' + q : '')
      return { method: op.method, relUrl, absUrl: new URL(relUrl, window.location.href).href, headers, bodyStr, bodyData, mediaKind: mt ? mt.kind : null }
    }

    // Recent-value history (combobox suggestions). Bump histSeq after recording so dropdowns refresh.
    const histSeq = ref(0)
    const paramHistory = (p) => { void histSeq.value; return getValues(paramKey(props.operation.id, p)) }
    const bodyHist = (fieldKey) => { void histSeq.value; return getValues(bodyFieldKey(props.operation.id, fieldKey)) }
    // Forget a single remembered value (the per-suggestion "✕"); bump so the dropdown refreshes.
    const forgetParam = (p, value) => { removeValue(paramKey(props.operation.id, p), value); histSeq.value++ }
    const bodyForget = (fieldKey, value) => { removeValue(bodyFieldKey(props.operation.id, fieldKey), value); histSeq.value++ }
    // On Send, record the actually-used text/number params and top-level (form-mode) body fields.
    const recordHistory = () => {
      const opId = props.operation.id
      for (const p of paramState.value) if (p.control === 'text') recordValue(paramKey(opId, p), p.value)
      if (bodyNode.value && !useRaw.value && bodyNode.value.kind === 'object') {
        for (const f of bodyNode.value.fields) {
          if (f.node.kind === 'string' || f.node.kind === 'number') recordValue(bodyFieldKey(opId, f.key), f.node.value)
        }
      }
      histSeq.value++
    }

    // Non-blocking validation warnings (Form mode only — Raw JSON has its own linter). Advisory:
    // Send is never disabled, so genuinely-invalid requests can still be fired for endpoint testing.
    const paramWarnings = computed(() => {
      const out = []
      for (const p of paramState.value) {
        const label = `${p.in} ${p.name}`
        const v = p.value
        const empty = v == null || v === ''
        if (p.required && empty) { out.push({ path: label, message: 'required, but empty' }); continue }
        if (empty) continue
        const s = p.schema || {}
        const type = Array.isArray(s.type) ? s.type.filter(x => x !== 'null')[0] : s.type
        if (p.control === 'enum' && Array.isArray(p.options) && !p.options.map(String).includes(String(v))) out.push({ path: label, message: 'value not in the allowed set' })
        if (type === 'integer' || type === 'number') {
          const n = Number(v)
          if (Number.isNaN(n)) out.push({ path: label, message: 'not a number' })
          else {
            if (s.minimum != null && n < s.minimum) out.push({ path: label, message: `below minimum ${s.minimum}` })
            if (s.maximum != null && n > s.maximum) out.push({ path: label, message: `above maximum ${s.maximum}` })
            if (type === 'integer' && !Number.isInteger(n)) out.push({ path: label, message: 'not an integer' })
          }
        } else if (type === 'string' || !type) {
          const str = String(v)
          if (s.minLength != null && str.length < s.minLength) out.push({ path: label, message: `shorter than minLength ${s.minLength}` })
          if (s.maxLength != null && str.length > s.maxLength) out.push({ path: label, message: `longer than maxLength ${s.maxLength}` })
          if (s.pattern) { try { if (!new RegExp(s.pattern).test(str)) out.push({ path: label, message: `does not match pattern ${s.pattern}` }) } catch (e) { /* invalid spec pattern */ } }
        }
      }
      return out
    })
    const warnings = computed(() => {
      const w = [...paramWarnings.value]
      if (bodyNode.value && !useRaw.value && bodySupported.value) w.push(...collectBodyWarnings(bodyNode.value))
      return w
    })
    // Body warnings carry a JSONPath path ($, $.name, $.address.city, $[0].sku). Drop the cryptic
    // root for display: top-level → the bare field name, nested keep their dotted path, the whole body
    // → "body". Parameter paths (e.g. "query limit") don't start with $ and pass through unchanged.
    const fmtWarnPath = (p) => p === '$' ? 'body' : p.startsWith('$.') ? p.slice(2) : p.startsWith('$') ? p.slice(1) : p

    // Base filename for a downloaded response body (extension added by <ResponseView>).
    const downloadName = computed(() =>
      (props.operation.method + '-' + props.operation.path).replace(/[^\w.-]+/g, '_').replace(/^_+|_+$/g, '') || 'response')

    // The in-flight request's AbortController lives on the per-operation execState slice, so the user
    // can cancel a slow send (there is deliberately no automatic timeout — some endpoints legitimately
    // take minutes) and so a request keeps running, addressable, across operation switches.
    const cancel = () => { const c = props.execState.inflight; if (c) c.abort() }

    // Arm the timer that reveals the Cancel control CANCEL_DEBOUNCE_MS after a send starts. The seq guard
    // stops a superseded send's pending timer from re-showing Cancel over a newer call.
    const clearCancelTimer = (es) => { if (es.cancelTimer) { clearTimeout(es.cancelTimer); es.cancelTimer = null } }
    const armCancelTimer = (es, mySeq) => {
      clearCancelTimer(es)
      es.showCancel = false
      es.cancelTimer = setTimeout(() => { es.cancelTimer = null; if (es.seq === mySeq) es.showCancel = true }, CANCEL_DEBOUNCE_MS)
    }

    const send = async () => {
      rawError.value = ''
      let req
      try {
        req = buildRequest()
      } catch (e) {
        rawError.value = 'Invalid JSON body: ' + e.message
        useRaw.value = true
        return
      }
      recordHistory()
      // Capture the originating operation's slice and id NOW. After any `await` below, `props` reflects
      // whatever operation is currently selected, so every post-await write must go through these
      // captures — that is what routes the response back to the operation it was sent from.
      const es = props.execState
      const opId = props.operation.id
      // Re-sending replaces this operation's own prior in-flight call.
      if (es.inflight) es.inflight.abort()
      const ctrl = new AbortController()
      const mySeq = ++es.seq
      es.inflight = ctrl
      es.sending = true
      es.response = null
      armCancelTimer(es, mySeq)
      try {
        const t0 = performance.now()
        const res = await fetch(req.relUrl, { method: req.method, headers: req.headers, body: req.bodyData || req.bodyStr, signal: ctrl.signal })
        const dur = Math.round(performance.now() - t0)
        // Capture the exact bytes as a Blob; decode to text only for text-like (or unknown) types so
        // binary stays intact for preview/download. <ResponseView> renders from the Content-Type.
        const ct = res.headers.get('content-type') || ''
        const blob = await res.blob()
        const rawBody = isTextLike(ct) ? await blob.text() : null
        const hdrs = {}
        res.headers.forEach((v, k) => { hdrs[k] = v })
        // Superseded by a newer send for this operation — drop the stale result.
        if (es.seq !== mySeq) return
        const headerEntries = Object.entries(hdrs)
        es.response = {
          status: res.status, statusText: res.statusText, ok: res.ok, durationMs: dur,
          // The browser follows 3xx transparently (it won't expose the intermediate redirect to JS);
          // these two flags are the only signal that one happened, surfaced as a note in the view.
          redirected: res.redirected, finalUrl: res.url,
          contentType: ct, blob, rawBody, contentDisposition: res.headers.get('content-disposition') || '',
          // Structured rows back the per-header rendering (link resolution); headersText backs Copy.
          headersList: headerEntries.map(([name, value]) => ({ name, value })),
          headersText: headerEntries.map(([k, v]) => k + ': ' + v).join('\n')
        }
        if (props.onExecuted) props.onExecuted({ opId, req, response: es.response })
      } catch (e) {
        // Drop a superseded call's error/abort (e.g. the one a re-send just aborted) so it can't flash
        // "Request cancelled" over the newer in-flight; only the current call reports.
        if (es.seq !== mySeq) return
        // A user-driven cancel surfaces as a DOMException named AbortError — report it as a cancel, not
        // a network failure (the response view renders `cancelled` without the "Network error:" prefix).
        if (e.name === 'AbortError') es.response = { status: '—', statusText: '', ok: false, cancelled: true }
        else es.response = { status: '—', statusText: '', ok: false, networkError: e.message }
      } finally {
        if (es.inflight === ctrl) es.inflight = null
        if (es.seq === mySeq) { es.sending = false; clearCancelTimer(es); es.showCancel = false }
      }
    }

    // Reset this operation to a clean slate: abort any in-flight request (bumping seq so its aborted
    // call can't write a stale "cancelled" response), drop its in-memory response, forget its saved
    // form snapshot, and rebuild the form from schema defaults (no seed).
    const reset = () => {
      const es = props.execState
      if (es.inflight) es.inflight.abort()
      es.seq++
      es.inflight = null
      es.sending = false
      clearCancelTimer(es)
      es.showCancel = false
      es.response = null
      removeForm(props.operation.id)
      rebuild({ fresh: true })
    }

    onBeforeUnmount(cancel)
    onBeforeUnmount(() => clearCancelTimer(props.execState))
    onBeforeUnmount(() => flushSave(props.operation))

    // Ctrl/Cmd+Enter executes the request from anywhere on the operation. A capture-phase listener
    // runs before CodeMirror's default Mod-Enter ("insert blank line"), so it works inside the editor
    // too. Switch to the Try-it-out tab so the response is visible; ignore while a request is in flight.
    // Prefer the modern userAgentData.platform (e.g. "macOS"); fall back to the deprecated
    // navigator.platform, then the UA string, on browsers that don't expose it.
    const uaPlatform = navigator.userAgentData?.platform || navigator.platform || navigator.userAgent || ''
    const isMac = /Mac|iPhone|iPad/.test(uaPlatform)
    const sendHint = isMac ? '⌘ Enter' : 'Ctrl+Enter'
    const onExecKey = (e) => {
      if (!((e.ctrlKey || e.metaKey) && e.key === 'Enter')) return
      e.preventDefault()
      e.stopPropagation()
      if (!sending.value) { tab.value = 'try'; send() }
    }
    onMounted(() => document.addEventListener('keydown', onExecKey, true))
    onBeforeUnmount(() => document.removeEventListener('keydown', onExecKey, true))

    // Roving-focus arrow navigation for a tablist (Try/Schema, Form/Raw, Schema/Examples). ←/→ wrap
    // between the enabled tabs, Home/End jump to the ends; the moved-to tab is focused and activated
    // (selection follows focus, the standard tabs pattern). Activation itself stays on the @click.
    const onTabKeys = (e) => {
      if (!['ArrowLeft', 'ArrowRight', 'Home', 'End'].includes(e.key)) return
      e.preventDefault()
      const tabs = Array.from(e.currentTarget.parentElement.querySelectorAll('[role=tab]:not([disabled])'))
      const cur = tabs.indexOf(e.currentTarget)
      let next = cur
      if (e.key === 'ArrowLeft') next = cur > 0 ? cur - 1 : tabs.length - 1
      else if (e.key === 'ArrowRight') next = cur < tabs.length - 1 ? cur + 1 : 0
      else if (e.key === 'Home') next = 0
      else if (e.key === 'End') next = tabs.length - 1
      const el = tabs[next]
      if (el) { el.focus(); el.click() }
    }

    const copyToClipboard = async (text) => {
      if (await copyText(text)) flashCopied()
    }

    const copyOpId = async () => {
      if (await copyText(props.operation.operationId)) flashOpId()
    }

    const sqEscape = (s) => String(s).replace(/'/g, "'\\''")

    const copyCurl = async () => {
      let req
      try { req = buildRequest() } catch (e) { rawError.value = 'Invalid JSON body: ' + e.message; useRaw.value = true; return }
      const parts = [`curl -X ${req.method} '${req.absUrl}'`]
      for (const [k, v] of Object.entries(req.headers)) parts.push(`-H '${k}: ${v}'`)
      if (req.bodyData) {
        // multipart: -F per part; files reference a placeholder path the user edits (bytes can't be embedded).
        for (const [k, v] of req.bodyData.entries()) {
          parts.push(v instanceof File ? `-F '${sqEscape(k)}=@/path/to/${v.name}'` : `-F '${sqEscape(k)}=${sqEscape(v)}'`)
        }
      } else if (req.mediaKind === 'form') {
        for (const [k, v] of new URLSearchParams(req.bodyStr).entries()) parts.push(`--data-urlencode '${sqEscape(k)}=${sqEscape(v)}'`)
      } else if (req.bodyStr) {
        parts.push(`-d '${sqEscape(req.bodyStr)}'`)
      }
      await copyToClipboard(parts.join(' \\\n  '))
    }

    // JetBrains HTTP Client (.http) request: "METHOD url", header lines, a blank line, then the body —
    // pretty JSON, the urlencoded/raw string, or a multipart block with boundary + file references.
    const copyHttp = async () => {
      let req
      try { req = buildRequest() } catch (e) { rawError.value = 'Invalid JSON body: ' + e.message; useRaw.value = true; return }
      const lines = [`${req.method} ${req.absUrl}`]
      for (const [k, v] of Object.entries(req.headers)) lines.push(`${k}: ${v}`)

      if (req.bodyData) {
        const boundary = 'WebAppBoundary'
        lines.push(`Content-Type: multipart/form-data; boundary=${boundary}`)
        let text = lines.join('\n') + '\n'
        for (const [k, v] of req.bodyData.entries()) {
          text += `\n--${boundary}\n`
          if (v instanceof File) text += `Content-Disposition: form-data; name="${k}"; filename="${v.name}"\n\n< ./${v.name}\n`
          else text += `Content-Disposition: form-data; name="${k}"\n\n${v}\n`
        }
        text += `--${boundary}--\n`
        await copyToClipboard(text)
        return
      }

      let text = lines.join('\n')
      if (req.mediaKind === 'json' && req.bodyStr) text += '\n\n' + pretty(JSON.parse(req.bodyStr))
      else if (req.bodyStr) text += '\n\n' + req.bodyStr
      await copyToClipboard(text)
    }

    return {
      paramState, bodyTypes, mediaType, bodyMt, curKind, rebuildBody, bodyNode, bodySupported, useRaw, rawText, rawError, response, sending, cancel, copied, opIdCopied, copyOpId,
      tab, schemaView, requestRef, responseRefs, statusClass, editorSchema, mdBlock,
      requestExamples, reqCanPrefill, responseExamples, paramExampleGroups, hasAnyExamples, prefillRaw, prefillParam,
      setRaw, prettyRaw, send, reset, copyCurl, copyHttp, paramHistory, bodyHist, forgetParam, bodyForget, downloadName, warnings, fmtWarnPath, sendHint, onTabKeys,
      showCancel
    }
  },
  template: `
    <section class="op-panel">
      <header class="op-header">
        <span class="method" :class="'m-' + operation.method.toLowerCase()">{{ operation.method }}</span>
        <span class="op-path">{{ operation.path }}</span>
      </header>
      <div v-if="operation.operationId" class="op-id">
        <span class="op-id-label">Operation ID</span>
        <code class="op-id-value">{{ operation.operationId }}</code>
        <button type="button" class="op-id-copy" @click="copyOpId" aria-label="Copy operation ID"
          v-tip="'Copy operation ID'">
          <svg viewBox="0 0 24 24" width="14" height="14" fill="none" stroke="currentColor" stroke-width="2"
            stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">
            <rect x="9" y="9" width="13" height="13" rx="2" />
            <path d="M5 15H4a2 2 0 0 1-2-2V4a2 2 0 0 1 2-2h9a2 2 0 0 1 2 2v1" />
          </svg>
        </button>
        <span v-if="opIdCopied" class="copied-note" role="status">✓ Copied</span>
      </div>
      <h2 v-if="operation.summary" class="op-summary">{{ operation.summary }}</h2>
      <div v-if="operation.deprecated" class="deprecated-banner">⚠ This operation is deprecated.</div>
      <div v-if="operation.description" class="op-desc" v-html="mdBlock(operation.description)"></div>
      <p v-if="operation.externalDocs && operation.externalDocs.url" class="op-extdocs">
        <a :href="operation.externalDocs.url" target="_blank" rel="noopener">{{ operation.externalDocs.description || 'External documentation' }} ↗</a>
      </p>

      <div class="op-tabs" role="tablist" aria-label="Operation views">
        <button type="button" role="tab" :aria-selected="tab === 'try'" :tabindex="tab === 'try' ? 0 : -1"
          :class="{ active: tab === 'try' }" @click="tab = 'try'" @keydown="onTabKeys">Try it out</button>
        <button type="button" role="tab" :aria-selected="tab === 'schema'" :tabindex="tab === 'schema' ? 0 : -1"
          :class="{ active: tab === 'schema' }" @click="tab = 'schema'" @keydown="onTabKeys">Schema</button>
      </div>

      <div v-show="tab === 'try'" role="tabpanel">
        <ParamInputs v-if="paramState.length" :params="paramState" :history="paramHistory" :forget="forgetParam" />

        <div v-if="bodyTypes.length" class="body-section">
          <div class="body-head">
            <h3>Request body</h3>
            <select v-if="bodyTypes.length > 1" class="media-select" v-model="mediaType" @change="rebuildBody"
                    v-tip="'Request body content type'">
              <option v-for="t in bodyTypes" :key="t.mediaType" :value="t.mediaType">{{ t.mediaType }}</option>
            </select>
            <div v-if="curKind === 'json'" class="body-tabs" role="tablist" aria-label="Body editor">
              <button type="button" role="tab" :aria-selected="!useRaw" :tabindex="!useRaw ? 0 : -1"
                :class="{ active: !useRaw }" :disabled="!bodySupported" @click="setRaw(false)" @keydown="onTabKeys">Form</button>
              <button type="button" role="tab" :aria-selected="useRaw" :tabindex="useRaw ? 0 : -1"
                :class="{ active: useRaw }" @click="setRaw(true)" @keydown="onTabKeys">Raw JSON</button>
            </div>
          </div>

          <template v-if="curKind === 'json'">
            <div v-if="!bodySupported" class="unsupported">This body can't be rendered as a form — use Raw JSON.</div>
            <div v-show="!useRaw && bodySupported" class="form-body"><SchemaField :node="bodyNode" :required="true" :root-hist="bodyHist" :root-forget="bodyForget" /></div>
            <div v-show="useRaw" class="raw-body">
              <JsonEditor v-if="useRaw" v-model="rawText" :schema="editorSchema" />
              <div class="raw-tools">
                <button type="button" class="btn-mini" @click="prettyRaw">Pretty-print</button>
                <span v-if="rawError" class="raw-error">{{ rawError }}</span>
              </div>
            </div>
          </template>

          <template v-else-if="curKind === 'form' || curKind === 'multipart'">
            <p class="body-encoding-note hint">{{ curKind === 'multipart' ? 'Sent as multipart/form-data — supports file uploads.' : 'Sent as application/x-www-form-urlencoded.' }}</p>
            <div v-if="!bodySupported" class="unsupported">This body can't be rendered as a form.</div>
            <div v-else class="form-body"><SchemaField :node="bodyNode" :required="true" :root-hist="bodyHist" :root-forget="bodyForget" /></div>
          </template>

          <template v-else>
            <p class="body-encoding-note hint">Raw {{ mediaType }} body.</p>
            <textarea class="raw-other" v-model="rawText" rows="8" spellcheck="false" placeholder="request body"></textarea>
          </template>
        </div>
        <p v-if="rawError && !bodyTypes.length" class="raw-error">{{ rawError }}</p>

        <div v-if="warnings.length" class="warnings" role="status">
          <div class="warnings-head">⚠ {{ warnings.length }} warning{{ warnings.length > 1 ? 's' : '' }} — you can still send</div>
          <ul>
            <li v-for="(w, i) in warnings" :key="i"><code>{{ fmtWarnPath(w.path) }}</code> — {{ w.message }}</li>
          </ul>
        </div>

        <div class="send-bar">
          <span class="send-cta">
            <!-- Send and Cancel are two distinct controls, not one morphing button. On click Send is
                 disabled at once (so a double-click can't fire a second action — and can never land on
                 Cancel) but holds its green look. Only if the request is still in flight past the debounce
                 (CANCEL_DEBOUNCE_MS — see showCancel) does Send recede to a quiet "Sending…" ghost and a
                 separate outline Cancel appear beside it. A fast request settles within the debounce, so
                 it shows neither the grey morph nor a flashed Cancel. The pre-cancel class keeps Send
                 green while disabled in that pre-debounce window. -->
            <span class="send-row" role="status">
              <button class="btn-send" type="button" :disabled="sending"
                :class="{ 'pre-cancel': sending && !showCancel }"
                v-tip="sending ? 'Request in flight' : 'Send the request (' + sendHint + ')'"
                @click="send">{{ showCancel ? 'Sending…' : 'Send' }}</button>
              <button v-if="showCancel" class="btn-cancel" type="button"
                v-tip="'Cancel the in-flight request'" @click="cancel">Cancel</button>
            </span>
            <span v-if="!sending" class="kbd-hint" v-tip="'Keyboard shortcut to send the request'">{{ sendHint }}</span>
          </span>
          <!-- Form utilities (serialize / reset the current form) on their own row, kept clear of the
               execute action below. The bar is column-reversed so this row sits on top visually while
               Send stays first in DOM/tab order. -->
          <div class="util-row">
            <button class="btn-mini" type="button" @click="copyCurl">Copy as cURL</button>
            <button class="btn-mini" type="button" @click="copyHttp">Copy as JetBrains .http</button>
            <button class="btn-mini danger btn-reset-op" type="button" @click="reset"
              v-tip="'Reset this operation — clear its inputs, saved form, and last response'">Reset</button>
            <span v-if="copied" class="copied-note" role="status">✓ Copied</span>
          </div>
        </div>

        <ResponseView v-if="response" :resp="response" :name="downloadName" />
      </div>

      <div v-show="tab === 'schema'" class="schema-doc" role="tabpanel">
        <div class="body-tabs schema-toggle" role="tablist" aria-label="Schema views">
          <button type="button" role="tab" :aria-selected="schemaView === 'schema'" :tabindex="schemaView === 'schema' ? 0 : -1"
            :class="{ active: schemaView === 'schema' }" @click="schemaView = 'schema'" @keydown="onTabKeys">Schema</button>
          <button type="button" role="tab" :aria-selected="schemaView === 'examples'" :tabindex="schemaView === 'examples' ? 0 : -1"
            :class="{ active: schemaView === 'examples' }" @click="schemaView = 'examples'" @keydown="onTabKeys">Examples</button>
        </div>

        <template v-if="schemaView === 'schema'">
          <template v-if="requestRef">
            <h3>Request body</h3>
            <div class="schema-block"><SchemaTree :node="requestRef.tree" /></div>
          </template>

          <h3>Responses</h3>
          <div v-for="r in responseRefs" :key="r.status" class="schema-block">
            <div class="resp-line"><span class="resp-code" :class="statusClass(r.status)">{{ r.status }}</span> <span class="resp-text">{{ r.description }}</span></div>
            <div v-if="r.headers && r.headers.length" class="resp-headers-doc">
              <div class="resp-headers-doc-title">Headers</div>
              <div v-for="h in r.headers" :key="h.name" class="resp-headers-doc-row">
                <span class="stree-name">{{ h.name }}</span>
                <span v-if="h.schema && h.schema.type" class="stree-type">{{ h.schema.type }}</span>
                <span v-if="h.description" class="stree-desc">{{ h.description }}</span>
              </div>
            </div>
            <SchemaTree v-if="r.schema" :node="r.tree" />
            <div v-else class="hint">No JSON body.</div>
          </div>
        </template>

        <template v-else>
          <p v-if="!hasAnyExamples" class="hint">No examples provided in the spec.</p>

          <template v-if="paramExampleGroups.length">
            <h3>Parameters</h3>
            <div v-for="g in paramExampleGroups" :key="g.in + '-' + g.name" class="schema-block">
              <div class="example-group-head"><span class="stree-name">{{ g.name }}</span> <span class="stree-type">{{ g.in }}</span></div>
              <ExampleCard v-for="e in g.examples" :key="e.name" v-bind="e"
                :can-prefill="true" :compact="true" prefill-label="Apply" @prefill="prefillParam(g, $event)" />
            </div>
          </template>

          <template v-if="requestExamples.length">
            <h3>Request body</h3>
            <div class="schema-block request-examples">
              <ExampleCard v-for="e in requestExamples" :key="e.name" v-bind="e"
                :can-prefill="reqCanPrefill" @prefill="prefillRaw" />
            </div>
          </template>

          <template v-for="r in responseRefs" :key="r.status">
            <template v-if="responseExamples(r).length">
              <h3>Response {{ r.status }}</h3>
              <div class="schema-block response-examples">
                <ExampleCard v-for="e in responseExamples(r)" :key="e.name" v-bind="e" />
              </div>
            </template>
          </template>
        </template>
      </div>
    </section>
  `
}
