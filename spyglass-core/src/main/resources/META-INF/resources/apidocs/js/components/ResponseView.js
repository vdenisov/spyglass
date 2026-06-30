import { ref, computed, watch, onBeforeUnmount } from 'vue'
import { copyText } from '../clipboard.js'
import { resolveHeaderLink, runBodyTransformers } from '../extensions.js'
import { specRoot } from '../spec.js'
import { loadJson, saveJson, RESPONSE_PRETTY_KEY, RESPONSE_DECODED_KEY } from '../storage.js'
import { formatBytes, statusKind, filenameFromDisposition } from '../format.js'
import { useFlash } from '../useFlash.js'
import CodeViewer from './CodeViewer.js'

// Above this body size (chars) we don't parse/pretty-print/JSON-highlight: a multi-MB response would
// otherwise freeze the tab on JSON.parse + JSON.stringify + CodeMirror's JSON folding/highlighting.
// Past the limit the body renders as plain, unformatted text (still searchable and downloadable).
const PRETTY_LIMIT = 2_000_000

// A small curated MIME→extension map for the download filename, used only when the response carries
// no Content-Disposition. Special cases (jpeg→jpg, gzip→gz, octet-stream→bin) that the subtype
// heuristic below would get wrong or ugly; common ones are listed for clarity.
const MIME_EXT = {
  'application/json': 'json', 'application/pdf': 'pdf', 'application/zip': 'zip',
  'application/gzip': 'gz', 'application/xml': 'xml', 'application/octet-stream': 'bin',
  'text/plain': 'txt', 'text/html': 'html', 'text/csv': 'csv', 'text/xml': 'xml',
  'image/png': 'png', 'image/jpeg': 'jpg', 'image/gif': 'gif', 'image/webp': 'webp',
  'image/svg+xml': 'svg'
}

function extFor(contentType) {
  const c = (contentType || '').split(';')[0].trim().toLowerCase()
  if (MIME_EXT[c]) return MIME_EXT[c]
  // Heuristic: for many types the (cleaned) subtype is a sensible extension —
  // application/vnd.foo+json → json, image/x-bar → bar.
  const sub = c.split('/')[1] || ''
  const cleaned = (sub.split('+').pop() || sub).replace(/^x-/, '').replace(/^vnd\./, '')
  return /^[a-z0-9.-]{1,8}$/.test(cleaned) ? cleaned : 'bin'
}

// Displays the result of a "try it out" request. Rendering is chosen from the response Content-Type:
// JSON gets the read-only CodeMirror viewer with an optional pretty-print; text/HTML/XML render as
// plain text; images get an inline thumbnail; anything else binary offers download only (we never
// dump raw bytes into the page).
export default {
  name: 'ResponseView',
  components: { CodeViewer },
  props: {
    resp: { type: Object, required: true },
    // Base filename for the Download button when the response carries no Content-Disposition.
    name: { type: String, default: 'response' },
    // The operation this response belongs to, passed to body transformers as ctx.operation (carries
    // the operation's x-* vendor extensions). Null when no operation context is available.
    operation: { type: Object, default: null }
  },
  setup(props) {
    // Pretty-print preference persists like a UI setting (not cleared by "Clear").
    const pretty = ref(loadJson(localStorage, RESPONSE_PRETTY_KEY, true) !== false)
    watch(pretty, (v) => saveJson(localStorage, RESPONSE_PRETTY_KEY, v))
    // Decoded-view preference, same persistence convention. Defaults on so that when a host
    // extension can decode the body it shows the readable form first; raw stays one click away.
    const decoded = ref(loadJson(localStorage, RESPONSE_DECODED_KEY, true) !== false)
    watch(decoded, (v) => saveJson(localStorage, RESPONSE_DECODED_KEY, v))
    const { flag: copied, flash: flashCopied } = useFlash()

    // { ok, value } — ok is false on empty or unparseable bodies (distinguishes a real JSON `null`).
    // An over-limit body is reported tooLarge WITHOUT parsing, so `kind` falls back to plain text and
    // the expensive parse/stringify/highlight paths are skipped entirely (see PRETTY_LIMIT).
    const parsedJson = computed(() => {
      const raw = props.resp.rawBody
      if (raw == null || raw === '') return { ok: false }
      if (raw.length > PRETTY_LIMIT) return { ok: false, tooLarge: true }
      try { return { ok: true, value: JSON.parse(raw) } } catch (e) { return { ok: false } }
    })
    const tooLarge = computed(() => !!parsedJson.value.tooLarge)

    const kind = computed(() => {
      const ct = (props.resp.contentType || '').toLowerCase()
      if (ct.startsWith('image/')) return 'image'
      if (ct.includes('json')) return parsedJson.value.ok ? 'json' : 'text'
      if (ct.startsWith('text/') || ct.includes('xml') || ct.includes('html') || ct.includes('javascript') || ct.includes('csv')) return 'text'
      if (!ct) return parsedJson.value.ok ? 'json' : 'text' // unknown type: sniff for JSON
      return 'binary'
    })
    const isText = computed(() => kind.value === 'json' || kind.value === 'text')

    // Runs the registered body transformers against the parsed JSON value (JSON responses only),
    // returning { value, applied }. The registry is reactive, so this re-evaluates once extensions
    // finish registering — the same way headerRows picks up a late-registered resolver. ctx carries
    // the operation (with its x-* extensions) and the full spec so a transformer can read its own
    // config; responseSchema is null here (this view is schema-unaware).
    const decodedResult = computed(() => {
      if (kind.value !== 'json' || !parsedJson.value.ok) return { value: null, applied: false }
      return runBodyTransformers(parsedJson.value.value, {
        operation: props.operation,
        responseSchema: null,
        contentType: props.resp.contentType,
        status: props.resp.status,
        spec: specRoot()
      })
    })
    // Whether any transformer applies to this response — gates the Decoded toggle's visibility.
    const canDecode = computed(() => decodedResult.value.applied)

    const displayText = computed(() => {
      if (kind.value === 'json' && decoded.value && canDecode.value) return JSON.stringify(decodedResult.value.value, null, 2)
      if (kind.value === 'json' && pretty.value && parsedJson.value.ok) return JSON.stringify(parsedJson.value.value, null, 2)
      return props.resp.rawBody || ''
    })

    const sizeText = computed(() => formatBytes(props.resp.blob ? props.resp.blob.size : (props.resp.rawBody || '').length))

    // Object URL for the image preview, recreated per response and revoked to avoid leaks.
    const imageUrl = ref('')
    const refreshImage = () => {
      if (imageUrl.value) { URL.revokeObjectURL(imageUrl.value); imageUrl.value = '' }
      if (kind.value === 'image' && props.resp.blob) imageUrl.value = URL.createObjectURL(props.resp.blob)
    }
    watch(() => props.resp, refreshImage, { immediate: true })
    onBeforeUnmount(() => { if (imageUrl.value) URL.revokeObjectURL(imageUrl.value) })

    const copy = async () => {
      if (await copyText(displayText.value)) flashCopied()
    }

    // Response headers as rows, with an optional deep link per value resolved by registered
    // extensions (see extensions.js). resolveHeaderLink reads the reactive resolver registry, so a
    // resolver registered while extensions load is picked up. Every response sets headersList (an
    // error response has none, hence the [] fallback); headersText backs Copy only.
    const headerRows = computed(() =>
      (props.resp.headersList || []).map(({ name, value }) => ({ name, value, href: resolveHeaderLink(name, value) })))
    const { flag: headersCopied, flash: flashHeaders } = useFlash()
    const copyHeaders = async () => {
      if (await copyText(props.resp.headersText || '')) flashHeaders()
    }

    const downloadFilename = computed(() =>
      filenameFromDisposition(props.resp.contentDisposition) || (props.name + '.' + extFor(props.resp.contentType)))

    const download = () => {
      // Download the exact bytes (Blob), preserving the MIME type; fall back to a text Blob if needed.
      const blob = props.resp.blob || new Blob([props.resp.rawBody || ''], { type: props.resp.contentType || 'application/octet-stream' })
      const url = URL.createObjectURL(blob)
      const a = document.createElement('a')
      a.href = url
      a.download = downloadFilename.value
      document.body.appendChild(a)
      a.click()
      a.remove()
      setTimeout(() => URL.revokeObjectURL(url), 0)
    }

    return {
      pretty, decoded, canDecode, copied, kind, isText, tooLarge, displayText, sizeText, imageUrl, downloadFilename, copy, download,
      headerRows, headersCopied, copyHeaders, statusKind
    }
  },
  template: `
    <div class="response">
      <div class="resp-status" :class="statusKind(resp.status)" role="status">
        <span class="code">{{ resp.status }} {{ resp.statusText }}</span>
        <span v-if="resp.durationMs != null" class="dur">{{ resp.durationMs }} ms</span>
        <span v-if="resp.contentType" class="resp-ct">{{ resp.contentType }}</span>
      </div>
      <div v-if="resp.redirected" class="resp-redirect">↪ redirected to <code>{{ resp.finalUrl }}</code></div>
      <div v-if="resp.networkError" class="unsupported">Network error: {{ resp.networkError }}</div>
      <div v-else-if="resp.cancelled" class="unsupported">Request cancelled.</div>
      <template v-else>
        <div class="resp-toolbar">
          <label v-if="kind === 'json'" class="resp-pretty"><input type="checkbox" v-model="pretty" /> Pretty-print</label>
          <label v-if="kind === 'json' && canDecode" class="resp-decoded"><input type="checkbox" v-model="decoded" /> Decoded</label>
          <span v-if="tooLarge" class="hint resp-toolarge">Large response ({{ sizeText }}) — shown unformatted</span>
          <button v-if="isText" type="button" class="btn-mini" aria-live="polite" @click="copy">{{ copied ? '✓ Copied' : 'Copy' }}</button>
          <button type="button" class="btn-mini" @click="download">Download</button>
        </div>
        <details open>
          <summary>Body</summary>
          <div v-if="kind === 'image'" class="resp-image">
            <a :href="imageUrl" target="_blank" rel="noopener"><img class="resp-thumb" :src="imageUrl" alt="response image" /></a>
            <div class="hint">{{ resp.contentType }}, {{ sizeText }} — click to open full size, or download as <code>{{ downloadFilename }}</code>.</div>
          </div>
          <div v-else-if="kind === 'binary'" class="resp-binary">Binary response ({{ resp.contentType }}), {{ sizeText }} — download as <code>{{ downloadFilename }}</code>.</div>
          <CodeViewer v-else :value="displayText" :language="kind === 'json' ? 'json' : 'text'" />
        </details>
        <details>
          <summary>Headers</summary>
          <div class="resp-headers">
            <div class="resp-headers-toolbar">
              <button type="button" class="btn-mini" aria-live="polite" @click="copyHeaders">{{ headersCopied ? '✓ Copied' : 'Copy' }}</button>
            </div>
            <div v-for="(h, i) in headerRows" :key="i" class="resp-headers-row">
              <span class="rh-name">{{ h.name }}</span>
              <a v-if="h.href" class="rh-val rh-link" :href="h.href" target="_blank" rel="noopener">{{ h.value }}</a>
              <span v-else class="rh-val">{{ h.value }}</span>
            </div>
          </div>
        </details>
      </template>
    </div>
  `
}
