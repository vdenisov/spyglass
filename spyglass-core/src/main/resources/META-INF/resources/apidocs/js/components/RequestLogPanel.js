import { ref, computed, watch, onBeforeUnmount } from 'vue'
import { recordsForOp, deleteRecord, clear } from '../requestLog.js'
import { statusKind, statusLabel, formatBytes } from '../format.js'
import { copyText } from '../clipboard.js'
import { loadJson, saveJson, RESPONSE_PRETTY_KEY } from '../storage.js'
import CodeViewer from './CodeViewer.js'

// The user-facing Request Log: the current operation's own past executions, newest-first, below the
// live response. Each row shows timestamp · status · size and expands in place to the full request and
// response — a stored snapshot, never re-derived from the live spec. From an expanded row the user can
// Replay the request back into this operation's form, or Delete the entry; "Clear log" empties this
// operation's log. The list is scoped to one operation by opId, so it never shows a global feed.
//
// Records are immutable snapshots captured by requestLog.js. A body is stored EITHER as verbatim text
// (`body`) OR, when it was too large / binary / multipart, as a `bodyInfo` descriptor with no payload —
// rendered as a plain note (or a parts list), never an editor. Verbatim text bodies use CodeViewer
// (highlighting, selection, pretty-print), matching the live response view's affordances.
export default {
  name: 'RequestLogPanel',
  components: { CodeViewer },
  props: {
    // The current operation's id ("<METHOD> <path>"); the log is scoped to it.
    opId: { type: String, required: true },
    // How many entries render before the "… +X more" fold (display only — never eviction).
    foldN: { type: Number, default: 5 },
    // Bumped by the parent after each send so the panel re-reads the store once the write settles.
    reloadSeq: { type: Number, default: 0 }
  },
  emits: ['replay'],
  setup(props, { emit }) {
    // Per-record view-models, newest-first (the store returns oldest-first insertion order). Each is
    // computed once per reload (not per render), so the template only reads precomputed fields.
    const records = ref([])
    // The id of the single expanded row, or null when all are collapsed.
    const expandedId = ref(null)
    // Whether the fold is open (all entries shown) vs. just the first foldN.
    const showAll = ref(false)
    // Pretty-print preference, shared with the live response view via the same persisted key.
    const pretty = ref(loadJson(localStorage, RESPONSE_PRETTY_KEY, true) !== false)
    watch(pretty, (v) => saveJson(localStorage, RESPONSE_PRETTY_KEY, v))
    // The key of the most recently copied body ('req-<id>' / 'resp-<id>'), for the transient "✓ Copied".
    // A keyed flag rather than useFlash.js (which exposes a single boolean) because copy targets vary per
    // expanded row; the timer/teardown logic is the same useFlash provides.
    const copiedKey = ref('')
    let copyTimer = null
    // Monotonic token guarding against out-of-order async reads: an older recordsForOp() resolving after
    // a newer one (rapid op switch, or a delete racing a post-send reload) must not overwrite fresh data.
    let loadSeq = 0

    const reload = async () => {
      const seq = ++loadSeq
      const recs = await recordsForOp(props.opId)
      if (seq !== loadSeq) return
      records.value = recs.map(toViewModel).reverse()
      if (expandedId.value != null && !records.value.some(r => r.id === expandedId.value)) expandedId.value = null
    }

    // On an operation switch, synchronously clear the list and view state so the previous operation's
    // rows can't render under the new operation while the async read is in flight; the reload watch then
    // refills. The reload watch also fires on each post-send reloadSeq bump.
    watch(() => props.opId, () => { showAll.value = false; expandedId.value = null; records.value = [] })
    watch([() => props.opId, () => props.reloadSeq], reload, { immediate: true })

    const visible = computed(() => (showAll.value ? records.value : records.value.slice(0, props.foldN)))
    const hiddenCount = computed(() => Math.max(0, records.value.length - props.foldN))

    const toggle = (id) => { expandedId.value = expandedId.value === id ? null : id }
    const onReplay = (rec) => emit('replay', rec.params)
    const onDelete = async (id) => { await deleteRecord(id); await reload() }
    const onClear = async () => { await clear(props.opId); await reload() }

    const copy = async (text, key) => {
      if (!(await copyText(text || ''))) return
      copiedKey.value = key
      clearTimeout(copyTimer)
      copyTimer = setTimeout(() => { copiedKey.value = '' }, 1500)
    }
    onBeforeUnmount(() => clearTimeout(copyTimer))

    // Pretty-print a JSON text body for display (mirrors ResponseView.displayText); falls back to the
    // raw text if it doesn't parse. Plain-text bodies are returned unchanged.
    const bodyText = (bv) => {
      if (bv.isJson && pretty.value) {
        try { return JSON.stringify(JSON.parse(bv.text), null, 2) } catch (e) { return bv.text }
      }
      return bv.text
    }

    return {
      records, visible, hiddenCount, expandedId, showAll, pretty, copiedKey,
      toggle, onReplay, onDelete, onClear, copy, bodyText
    }
  },
  template: `
    <section v-if="records.length" class="request-log">
      <div class="rl-head">
        <h3>Request Log</h3>
        <button type="button" class="btn-mini danger btn-clear-log" @click="onClear"
          v-tip="'Clear this operation\\'s logged executions. Distinct from Clear headers, which leaves history untouched.'">Clear log</button>
      </div>
      <ul class="rl-list">
        <li v-for="rec in visible" :key="rec.id" class="rl-entry" :class="{ expanded: expandedId === rec.id }">
          <button type="button" class="rl-row" :aria-expanded="expandedId === rec.id" @click="toggle(rec.id)"
            v-tip="expandedId === rec.id ? 'Collapse' : 'Expand'">
            <span class="rl-caret" aria-hidden="true">{{ expandedId === rec.id ? '▾' : '▸' }}</span>
            <span class="rl-time">{{ rec.timeLabel }}</span>
            <span class="rl-status" :class="rec.statusClass">{{ rec.statusLabel }}</span>
            <span class="rl-size">{{ rec.sizeLabel }}</span>
          </button>

          <div v-if="expandedId === rec.id" class="rl-detail">
            <div class="rl-actions">
              <button type="button" class="btn-mini" @click="onReplay(rec)"
                v-tip="'Load this request back into the form (best-effort against the current schema)'">Replay</button>
              <button type="button" class="btn-mini danger" @click="onDelete(rec.id)" v-tip="'Delete this entry'">Delete</button>
            </div>

            <section class="rl-block">
              <h4 class="rl-block-head">Request</h4>
              <div class="rl-line">
                <span class="method" :class="'m-' + rec.method.toLowerCase()">{{ rec.method }}</span>
                <code class="rl-target">{{ rec.url }}</code>
              </div>
              <template v-if="rec.reqBody.mode === 'text'">
                <div class="rl-body-toolbar">
                  <label v-if="rec.reqBody.isJson" class="resp-pretty"><input type="checkbox" v-model="pretty" /> Pretty-print</label>
                  <button type="button" class="btn-mini" aria-live="polite" @click="copy(bodyText(rec.reqBody), 'req-' + rec.id)">{{ copiedKey === 'req-' + rec.id ? '✓ Copied' : 'Copy' }}</button>
                </div>
                <CodeViewer :value="bodyText(rec.reqBody)" :language="rec.reqBody.language" />
              </template>
              <div v-else-if="rec.reqBody.mode === 'note'" class="rl-body-note">{{ rec.reqBody.note }}</div>
              <div v-else-if="rec.reqBody.mode === 'parts'" class="rl-parts">
                <div v-for="(p, i) in rec.reqBody.parts" :key="i" class="rl-part">
                  <span class="rl-part-name">{{ p.name }}</span>
                  <span v-if="p.kind === 'file'" class="rl-part-file">📎 {{ p.filename }} <span class="rl-part-meta">({{ p.detail }})</span></span>
                  <span v-else-if="p.kind === 'text'" class="rl-part-val">{{ p.value }}</span>
                  <span v-else class="rl-part-meta">{{ p.detail }}</span>
                </div>
              </div>
              <details v-if="rec.reqHeaderRows.length" class="rl-headers-details">
                <summary>Headers</summary>
                <div class="resp-headers">
                  <div v-for="(h, i) in rec.reqHeaderRows" :key="i" class="resp-headers-row">
                    <span class="rh-name">{{ h.name }}</span><span class="rh-val">{{ h.value }}</span>
                  </div>
                </div>
              </details>
            </section>

            <section class="rl-block">
              <h4 class="rl-block-head">Response</h4>
              <div class="resp-status" :class="rec.statusClass">
                <span class="code">{{ rec.statusLabel }}</span>
                <span v-if="rec.durationMs != null" class="dur">{{ rec.durationMs }} ms</span>
                <span v-if="rec.respContentType" class="resp-ct">{{ rec.respContentType }}</span>
              </div>
              <div v-if="rec.finalUrl" class="resp-redirect">↪ redirected to <code>{{ rec.finalUrl }}</code></div>
              <template v-if="rec.respBody.mode === 'text'">
                <div class="rl-body-toolbar">
                  <label v-if="rec.respBody.isJson" class="resp-pretty"><input type="checkbox" v-model="pretty" /> Pretty-print</label>
                  <button type="button" class="btn-mini" aria-live="polite" @click="copy(bodyText(rec.respBody), 'resp-' + rec.id)">{{ copiedKey === 'resp-' + rec.id ? '✓ Copied' : 'Copy' }}</button>
                </div>
                <CodeViewer :value="bodyText(rec.respBody)" :language="rec.respBody.language" />
              </template>
              <div v-else-if="rec.respBody.mode === 'note'" class="rl-body-note">{{ rec.respBody.note }}</div>
              <details v-if="rec.respHeaderRows.length" class="rl-headers-details">
                <summary>Headers</summary>
                <div class="resp-headers">
                  <div v-for="(h, i) in rec.respHeaderRows" :key="i" class="resp-headers-row">
                    <span class="rh-name">{{ h.name }}</span><span class="rh-val">{{ h.value }}</span>
                  </div>
                </div>
              </details>
            </section>
          </div>
        </li>
      </ul>
      <button v-if="records.length > foldN" type="button" class="rl-more" @click="showAll = !showAll">{{ showAll ? 'Show less' : '… +' + hiddenCount + ' more' }}</button>
    </section>
  `
}

// --- view-model construction (pure, runs once per reload) ---------------------

// Maps a stored record to the flat shape the template renders, so per-row helpers (timestamp, status
// class, byte label, header rows, body descriptors) run once per record per reload instead of per render.
function toViewModel(rec) {
  return {
    id: rec.id,
    timeLabel: isoLocal(rec.ts),
    method: rec.request.method,
    url: rec.request.url,
    status: rec.response.status,
    statusLabel: statusLabel(rec.response.status, rec.response.statusText),
    statusClass: statusKind(rec.response.status),
    durationMs: rec.response.durationMs,
    respContentType: rec.response.contentType,
    sizeLabel: formatBytes(rec.response.size),
    finalUrl: redirectTarget(rec),
    reqHeaderRows: headerRows(rec.request.headers),
    respHeaderRows: headerRows(rec.response.headers),
    reqBody: bodyView(rec.request.body, rec.request.bodyInfo, rec.request.contentType),
    respBody: bodyView(rec.response.body, rec.response.bodyInfo, rec.response.contentType),
    // The form snapshot, passed straight through for Replay.
    params: rec.request.params
  }
}

// Browser-independent local timestamp "YYYY-MM-DD HH:mm:ss" (avoids toLocaleString's month-first/locale
// ambiguity in a developer log).
function isoLocal(ts) {
  const d = new Date(ts)
  const p = (n) => String(n).padStart(2, '0')
  return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())} ${p(d.getHours())}:${p(d.getMinutes())}:${p(d.getSeconds())}`
}

// The browser follows 3xx transparently; a finalUrl that differs from the request URL is the only
// signal a redirect happened (mirrors the live response's redirect note). '' when none.
function redirectTarget(rec) {
  const f = rec.response.finalUrl
  return f && f !== rec.request.url ? f : ''
}

function headerRows(headers) {
  return Object.entries(headers || {}).map(([name, value]) => ({ name, value }))
}

// Resolves a stored body (verbatim text, a bodyInfo descriptor, or nothing) into a render descriptor:
//   { mode: 'text', text, isJson, language } | { mode: 'note', note } | { mode: 'parts', parts } | { mode: 'none' }
function bodyView(body, bodyInfo, contentType) {
  if (body != null) {
    const isJson = (contentType || '').toLowerCase().includes('json')
    return { mode: 'text', text: body, isJson, language: isJson ? 'json' : 'text' }
  }
  if (bodyInfo) {
    if (bodyInfo.kind === 'multipart') return { mode: 'parts', parts: bodyInfo.parts.map(partView) }
    return { mode: 'note', note: noteFor(bodyInfo) }
  }
  return { mode: 'none' }
}

// A plain, human note for an elided body (no editor, no Copy) — distinct wording for the over-cap and
// binary cases, both honest about the true byte count.
function noteFor(info) {
  const size = formatBytes(info.bytes)
  const ct = info.contentType || 'unknown type'
  if (info.kind === 'binary') {
    const named = info.filename ? `, "${info.filename}"` : ''
    return `Binary body not stored — ${size} of ${ct}${named}.`
  }
  return `Body not stored — ${size} of ${ct} (too large).`
}

// A multipart part as a render descriptor: a file (name + size/type, never contents), a small text part
// (its value), or an over-cap text part (byte count only).
function partView(p) {
  if (p.filename != null) {
    return { name: p.name, kind: 'file', filename: p.filename, detail: formatBytes(p.bytes) + (p.contentType ? ', ' + p.contentType : '') }
  }
  if (p.value != null) return { name: p.name, kind: 'text', value: p.value }
  return { name: p.name, kind: 'omitted', detail: formatBytes(p.bytes) + ' (too large)' }
}
