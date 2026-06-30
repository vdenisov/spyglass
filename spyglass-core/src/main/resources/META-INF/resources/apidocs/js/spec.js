import { reactive } from 'vue'
import { isSafeHref } from './config.js'

// The loaded OpenAPI document. Set by loadSpec and used as the resolution root for $ref.
let ROOT = null

// The raw spec response text and its ETag (if the host sent one), captured at load. The update
// check (Signal B, useUpdateCheck.js) hashes this text once for its baseline and replays the ETag
// as an opportunistic If-None-Match on later polls. Kept here because loadSpec is the single place
// the bytes are read; a separate baseline fetch could race a Blue/Green swap and hash a different
// deployment than the one this tab rendered.
let RAW_TEXT = null
let SPEC_ETAG = null

// Sentinel returned by serializeNode for fields that must not appear in the payload.
export const OMIT = Symbol('omit')

// Stable ids for editable list rows (array items / map entries) so Vue keys don't rely on index.
let seq = 0
const nextId = () => ++seq

export async function loadSpec(url) {
  const res = await fetch(url, { headers: { Accept: 'application/json' } })
  if (!res.ok) throw new Error(`Failed to load spec from ${url}: HTTP ${res.status}`)
  // Read the body as text first (not res.json()) so the update check can hash the exact bytes the
  // server sent for its baseline; parse from that same text. Capturing the ETag here lets a later
  // poll send a matching If-None-Match when the host runs a validator (e.g. ShallowEtagHeaderFilter).
  RAW_TEXT = await res.text()
  SPEC_ETAG = res.headers.get('ETag')
  ROOT = JSON.parse(RAW_TEXT)
  return ROOT
}

// The loaded OpenAPI document. Exposed so callers can hand a validator the full
// component namespace, letting internal `#/components/...` $refs resolve.
export function specRoot() {
  return ROOT
}

// The raw text of the loaded spec response, for the update check's Signal-B baseline hash.
export function specRawText() {
  return RAW_TEXT
}

// The ETag the spec response carried at load (or null) — replayed as an opportunistic If-None-Match.
export function specEtag() {
  return SPEC_ETAG
}

// --- $ref resolution -------------------------------------------------------

function resolveRef(ref) {
  if (typeof ref !== 'string' || !ref.startsWith('#/')) return null
  const parts = ref.slice(2).split('/').map(p => p.replace(/~1/g, '/').replace(/~0/g, '~'))
  let cur = ROOT
  for (const p of parts) {
    cur = cur == null ? null : cur[p]
    if (cur == null) return null
  }
  return cur
}

// Follows a chain of $refs, tracking which refs were seen on the current path so that
// recursive schemas can be detected (and rendered as raw JSON rather than looping forever).
function deref(schema, seen) {
  const path = seen ? new Set(seen) : new Set()
  let s = schema
  while (s && s.$ref) {
    if (path.has(s.$ref)) return { schema: s, cyclic: true, seen: path }
    path.add(s.$ref)
    s = resolveRef(s.$ref)
  }
  return { schema: s || {}, cyclic: false, seen: path }
}

// --- schema normalization --------------------------------------------------

// A string schema whose format marks it as file content (raw bytes or base64) — rendered as a file
// input in a multipart body.
function isBinaryFormat(format) {
  return format === 'binary' || format === 'byte'
}

// OpenAPI 3.1 allows `type` to be an array (e.g. ["string", "null"]). Collapse it to a single
// concrete type, treating "null" as nullability. Returns undefined or "__mixed__" when ambiguous.
function normalizeType(schema) {
  const t = schema.type
  if (Array.isArray(t)) {
    const nonNull = t.filter(x => x !== 'null')
    if (nonNull.length === 1) return nonNull[0]
    if (nonNull.length === 0) return undefined
    return '__mixed__'
  }
  return t
}

// Shallow-merges an `allOf` composition of object sub-schemas into one object schema.
// Returns { __unsupported, __reason } when the composition isn't a plain object merge.
function mergeAllOf(schema, seen) {
  if (!schema || !Array.isArray(schema.allOf)) return schema
  const merged = { type: 'object', properties: {}, required: [] }
  const parts = [...schema.allOf]
  if (schema.properties) parts.push({ type: 'object', properties: schema.properties, required: schema.required })
  for (const part of parts) {
    const d = deref(part, seen)
    const p = d.schema
    // A cyclic allOf member is the discriminator-inheritance pattern: a subtype's allOf points back
    // at its polymorphic base (which in turn lists that subtype in its oneOf). Don't re-expand the
    // base's composition — resolve it one level and pull in only its shared scalar properties.
    if (d.cyclic) {
      const base = (part && part.$ref) ? (resolveRef(part.$ref) || {}) : {}
      // The base may itself be an allOf composition (it extends a common root); merge that so the
      // subtype inherits the root's scalars too, not just the base's own properties. `seen` already
      // carries the cyclic ref, so this resolves one more level without re-expanding into a loop.
      const baseMerged = Array.isArray(base.allOf) ? mergeAllOf(base, d.seen) : base
      const bp = baseMerged.__unsupported ? base : baseMerged
      if (bp.properties) Object.assign(merged.properties, bp.properties)
      if (bp.required) merged.required.push(...bp.required)
      continue
    }
    if (Array.isArray(p.allOf)) {
      const m = mergeAllOf(p, d.seen)
      if (m.__unsupported) return m
      Object.assign(merged.properties, m.properties)
      merged.required.push(...(m.required || []))
      continue
    }
    // A polymorphic base used as an allOf member contributes only its shared properties here; its
    // oneOf/anyOf branches are chosen via the variant selector, not merged into every subtype.
    if (Array.isArray(p.oneOf) || Array.isArray(p.anyOf)) {
      if (p.properties) Object.assign(merged.properties, p.properties)
      if (p.required) merged.required.push(...p.required)
      continue
    }
    const t = normalizeType(p)
    if (t && t !== 'object') return { __unsupported: true, __reason: 'allOf with non-object member' }
    if (p.properties) Object.assign(merged.properties, p.properties)
    if (p.required) merged.required.push(...p.required)
  }
  // Carry a top-level oneOf/anyOf/discriminator through the merge so a polymorphic base that ALSO
  // extends a common root (allOf) still renders its variant selector — the merged properties then act
  // as the shared base each branch inherits via the cyclic-allOf recovery above. (No effect on a plain
  // allOf with no polymorphism keyword.)
  if (schema.oneOf) merged.oneOf = schema.oneOf
  if (schema.anyOf) merged.anyOf = schema.anyOf
  if (schema.discriminator) merged.discriminator = schema.discriminator
  return merged
}

// Compact, human-readable summary of the most useful validation constraints.
function constraintText(s, type) {
  const parts = []
  if (type === 'integer' || type === 'number') {
    if (s.minimum != null && s.maximum != null) parts.push(`${s.minimum}–${s.maximum}`)
    else if (s.minimum != null) parts.push(`≥ ${s.minimum}`)
    else if (s.maximum != null) parts.push(`≤ ${s.maximum}`)
    if (s.multipleOf != null) parts.push(`×${s.multipleOf}`)
  } else if (type === 'string') {
    if (s.minLength != null && s.maxLength != null) parts.push(`${s.minLength}–${s.maxLength} chars`)
    else if (s.minLength != null) parts.push(`≥ ${s.minLength} chars`)
    else if (s.maxLength != null) parts.push(`≤ ${s.maxLength} chars`)
    if (s.pattern) parts.push(`pattern: ${s.pattern}`)
  }
  return parts.join(', ')
}

// --- form-state node tree --------------------------------------------------
//
// The form binds to a tree of "nodes" (one per schema slot), kept separate from the JSON payload.
// This lets each node carry inclusion state ("omit" vs. "send empty/false") that serializeNode
// applies when producing the actual request body.

export function makeNode(schema, required = false, seen = new Set()) {
  const d = deref(schema, seen)
  if (d.cyclic) return reactive({ kind: 'unsupported', reason: 'recursive reference', required })

  const s = mergeAllOf(d.schema, d.seen)
  if (s.__unsupported) return reactive({ kind: 'unsupported', reason: s.__reason, required })

  if (Array.isArray(s.enum)) {
    const def = s.default
    const hasDef = def !== undefined
    return reactive({
      kind: 'enum', schema: s, required, options: s.enum, description: s.description,
      value: hasDef ? def : (required ? '' : s.enum[0]),
      include: required || hasDef
    })
  }

  // oneOf/anyOf: a choice between sub-schemas. Rendered as a variant selector plus the chosen
  // branch's form (see makeVariant). oneOf is always single-branch; an anyOf of object branches is
  // multi-branch (check any combination — they deep-merge), otherwise it falls back to single-branch.
  if (Array.isArray(s.oneOf) || Array.isArray(s.anyOf)) {
    const v = makeVariant(s, required, d.seen)
    if (v) return v
  }

  const type = normalizeType(s)

  // A free-form map (additionalProperties without fixed properties) becomes a key/value editor.
  const ap = s.additionalProperties
  const hasProps = s.properties && Object.keys(s.properties).length > 0
  if ((type === 'object' || (!type && ap !== undefined)) && !hasProps && ap !== undefined && ap !== false) {
    const valueSchema = (ap === true || typeof ap !== 'object') ? { type: 'string' } : ap
    return reactive({ kind: 'map', schema: s, required, description: s.description, valueSchema, valueSeen: d.seen, entries: [] })
  }

  if (type === 'object' || (!type && s.properties)) {
    const props = s.properties || {}
    const req = new Set(s.required || [])
    const fields = Object.entries(props).map(([key, propSchema]) => ({
      key,
      required: req.has(key),
      node: makeNode(propSchema, req.has(key), d.seen)
    }))
    const node = reactive({ kind: 'object', schema: s, required, include: required, fields, description: s.description })
    // A schema with fixed properties AND additionalProperties (a typed object that also permits extra
    // free-form keys) gets a map sub-editor alongside the declared fields. (The pure free-form map —
    // no fixed properties — is the `kind: 'map'` branch above; this is the both-at-once case.)
    if (ap !== undefined && ap !== false) {
      const valueSchema = (ap === true || typeof ap !== 'object') ? { type: 'string' } : ap
      node.additional = reactive({ kind: 'map', schema: s, required: false, valueSchema, valueSeen: d.seen, entries: [] })
    }
    return node
  }

  if (type === 'array' || (!type && s.items)) {
    const itemSchema = s.items || {}
    const id = deref(itemSchema, d.seen)
    const im = mergeAllOf(id.schema, id.seen)
    const itemEnum = Array.isArray(im.enum) ? im.enum : null
    const itemType = normalizeType(im)
    // An array of binary items is a multi-file upload (only meaningful in a multipart body). Some
    // generators emit the items with `format: binary` and no explicit `type`, so key off the format.
    if (isBinaryFormat(im.format)) {
      return reactive({ kind: 'file', schema: s, required, description: s.description, multiple: true, include: required, files: [] })
    }
    // Arrays of primitives become a single textarea (one entry per line); arrays of
    // objects/arrays keep add/remove rows since a textarea can't represent them. An item schema with
    // no type and no structure (e.g. springdoc emits `items: {}` for a List<String>, dropping the
    // item type) is treated as a free-form string so the array stays editable.
    const itemShapeless = !itemType && !im.properties && !im.items && !im.oneOf && !im.anyOf && !im.allOf && im.additionalProperties === undefined
    if (itemEnum || ['string', 'integer', 'number', 'boolean'].includes(itemType) || itemShapeless) {
      return reactive({ kind: 'array', primitive: true, schema: s, required, description: s.description, example: s.example, itemKind: itemEnum ? 'enum' : (itemType || 'string'), itemEnum, text: '' })
    }
    return reactive({ kind: 'array', primitive: false, schema: s, required, description: s.description, itemSchema, itemSeen: d.seen, items: [] })
  }

  if (type === 'string') {
    // A binary (or base64 byte) string is a file upload (only meaningful in a multipart body).
    if (isBinaryFormat(s.format)) {
      return reactive({ kind: 'file', schema: s, required, description: s.description, multiple: false, include: required, files: [] })
    }
    const def = s.default
    return reactive({
      kind: 'string', schema: s, required, format: s.format, description: s.description, example: s.example,
      minLength: s.minLength, maxLength: s.maxLength, pattern: s.pattern, constraints: constraintText(s, 'string'),
      value: def !== undefined ? String(def) : '', include: required || def !== undefined
    })
  }

  if (type === 'integer' || type === 'number') {
    const def = s.default
    return reactive({
      kind: 'number', schema: s, required, integer: type === 'integer', description: s.description, example: s.example,
      minimum: s.minimum, maximum: s.maximum, multipleOf: s.multipleOf, constraints: constraintText(s, type),
      value: def !== undefined ? def : ''
    })
  }

  if (type === 'boolean') {
    const def = s.default
    return reactive({
      kind: 'boolean', schema: s, required, description: s.description,
      value: def === true,
      include: required || def === true || def === false
    })
  }

  const reason = s.oneOf ? 'oneOf' : s.anyOf ? 'anyOf' : (type === '__mixed__' ? 'mixed types' : 'unsupported schema')
  return reactive({ kind: 'unsupported', reason, required })
}

// --- oneOf/anyOf variants --------------------------------------------------

// A human label for one branch of a oneOf/anyOf: the discriminator-mapping value, else the
// referenced schema name, else its title, else a positional fallback.
function variantLabel(rawSub, idx, mappingByRef) {
  if (rawSub && rawSub.$ref) {
    if (mappingByRef && mappingByRef[rawSub.$ref]) return mappingByRef[rawSub.$ref]
    return rawSub.$ref.split('/').pop()
  }
  if (rawSub && rawSub.title) return rawSub.title
  const t = rawSub ? normalizeType(rawSub) : undefined
  return t ? `${t} option ${idx + 1}` : `option ${idx + 1}`
}

// The discriminator value that selects a branch: the explicit mapping key, or (by OpenAPI
// convention, when no mapping is given) the referenced schema name. Null for inline branches.
function discValueFor(rawSub, mappingByRef) {
  if (!rawSub || !rawSub.$ref) return null
  return mappingByRef[rawSub.$ref] || rawSub.$ref.split('/').pop()
}

// (Re)builds the node for the currently-selected branch. With a discriminator, the chosen branch's
// discriminator property is prefilled with its value but left editable (so a mismatched value can
// still be sent for endpoint testing).
function buildVariantChild(node) {
  const v = node.variants[node.selected]
  const child = makeNode(v.schema, true, v.seen)
  if (node.discriminator && child.kind === 'object' && v.discValue != null) {
    const f = child.fields.find(f => f.key === node.discriminator)
    if (f) { f.node.value = v.discValue; f.node.include = true }
  }
  node.child = child
}

export function selectVariant(node, idx) {
  node.selected = idx
  buildVariantChild(node)
}

function makeVariant(s, required, seen) {
  const keyword = Array.isArray(s.oneOf) ? 'oneOf' : 'anyOf'
  // A pure {type:'null'} branch is just nullability, not a real choice — drop it.
  const subs = (s[keyword] || []).filter(sub => sub && sub.type !== 'null')
  if (subs.length === 0) return null

  const disc = s.discriminator || null
  const mappingByRef = {}
  if (disc && disc.mapping) for (const [val, ref] of Object.entries(disc.mapping)) mappingByRef[ref] = val

  const variants = subs.map((sub, i) => ({
    label: variantLabel(sub, i, mappingByRef),
    discValue: disc ? discValueFor(sub, mappingByRef) : null,
    schema: sub, seen
  }))

  // An anyOf whose branches are all objects can be filled together: each branch keeps its own form
  // and include toggle, and the serialized body deep-merges the checked branches (serializeNode).
  // Any non-object branch (e.g. string|integer) can't be merged into one value, so such an anyOf —
  // like every oneOf — stays single-select. The first branch is pre-checked when the body is required.
  if (keyword === 'anyOf' && variants.length > 1) {
    for (const v of variants) v.child = makeNode(v.schema, true, v.seen)
    if (variants.every(v => v.child.kind === 'object')) {
      variants.forEach((v, i) => { v.include = i === 0 && required })
      return reactive({
        kind: 'variant', schema: s, required, description: s.description, keyword,
        discriminator: null, multi: true, variants, include: required
      })
    }
    for (const v of variants) v.child = null // discard the probe children; the single-select path lazily rebuilds one
  }

  const node = reactive({
    kind: 'variant', schema: s, required, description: s.description, keyword,
    discriminator: disc ? disc.propertyName : null, multi: false,
    variants, selected: 0, include: required, child: null
  })
  buildVariantChild(node)
  return node
}

// Recursively merges plain-object source into target (used to combine the checked branches of a
// multi-branch anyOf). Arrays and scalars overwrite; overlapping object keys merge, last write wins.
function deepMerge(target, source) {
  for (const [k, v] of Object.entries(source)) {
    const cur = target[k]
    if (v && typeof v === 'object' && !Array.isArray(v) && cur && typeof cur === 'object' && !Array.isArray(cur)) {
      deepMerge(cur, v)
    } else {
      target[k] = v
    }
  }
  return target
}

export function addArrayItem(node) {
  // An item that's been added is always sent (required), so its object/array node serializes even
  // though its own optional sub-fields keep their individual include toggles.
  const item = makeNode(node.itemSchema, true, node.itemSeen)
  item._key = nextId()
  node.items.push(item)
}

export function addMapEntry(node) {
  node.entries.push({ _key: nextId(), key: '', node: makeNode(node.valueSchema, true, node.valueSeen) })
}

function coercePrimitive(line, kind) {
  if (kind === 'integer' || kind === 'number') {
    const n = Number(line)
    return Number.isNaN(n) ? line : n
  }
  if (kind === 'boolean') return line === 'true'
  return line
}

export function serializeNode(node) {
  switch (node.kind) {
    case 'string':
      if (!node.required && !node.include) return OMIT
      return node.value == null ? '' : node.value
    case 'number': {
      if (node.value === '' || node.value == null) return node.required ? null : OMIT
      const n = Number(node.value)
      return Number.isNaN(n) ? (node.required ? node.value : OMIT) : n
    }
    case 'boolean':
      if (!node.required && !node.include) return OMIT
      return node.value === true
    case 'enum':
      if (!node.required && !node.include) return OMIT
      if (node.value === '' || node.value == null) return node.required ? null : OMIT
      return node.value
    case 'object': {
      // Optional objects are sent only when explicitly included (then even as {}).
      if (!node.required && !node.include) return OMIT
      const obj = {}
      for (const f of node.fields) {
        const v = serializeNode(f.node)
        if (v !== OMIT) obj[f.key] = v
      }
      // Extra free-form keys (additionalProperties) merge in after the declared fields; a declared
      // field always wins a key collision, so the map can't clobber a documented property.
      if (node.additional) {
        for (const e of node.additional.entries) {
          if (!e.key || (e.key in obj)) continue
          const v = serializeNode(e.node)
          if (v !== OMIT) obj[e.key] = v
        }
      }
      return obj
    }
    case 'array': {
      if (node.primitive) {
        const lines = (node.text || '').split('\n').map(s => s.trim()).filter(s => s !== '')
        if (lines.length === 0) return node.required ? [] : OMIT
        return lines.map(l => coercePrimitive(l, node.itemKind))
      }
      const arr = []
      for (const it of node.items) {
        const v = serializeNode(it)
        if (v !== OMIT) arr.push(v)
      }
      if (arr.length === 0 && !node.required) return OMIT
      return arr
    }
    case 'map': {
      const obj = {}
      for (const e of node.entries) {
        if (!e.key) continue
        const v = serializeNode(e.node)
        if (v !== OMIT) obj[e.key] = v
      }
      if (Object.keys(obj).length === 0 && !node.required) return OMIT
      return obj
    }
    case 'variant': {
      if (!node.required && !node.include) return OMIT
      if (!node.multi) return serializeNode(node.child)
      // Multi-branch anyOf: deep-merge every checked branch's object into one body.
      const merged = {}
      let any = false
      for (const v of node.variants) {
        if (!v.include) continue
        const part = serializeNode(v.child)
        if (part === OMIT) continue
        any = true
        if (part && typeof part === 'object' && !Array.isArray(part)) deepMerge(merged, part)
      }
      return any || node.required ? merged : OMIT
    }
    case 'file':
      // Files never go into a JSON/urlencoded payload — they're handled by the multipart serializer.
      return OMIT
    default:
      return OMIT
  }
}

// Best-effort population of a node tree from a parsed JSON value (used when switching back
// from raw-JSON mode to the form).
export function importValue(node, value) {
  switch (node.kind) {
    case 'string':
      node.value = value == null ? '' : String(value)
      node.include = value !== undefined
      break
    case 'number':
      node.value = value == null ? '' : value
      node.include = value !== undefined
      break
    case 'boolean':
      if (typeof value === 'boolean') { node.value = value; node.include = true }
      break
    case 'enum':
      if (value != null) { node.value = value; node.include = true }
      break
    case 'object':
      if (value && typeof value === 'object' && !Array.isArray(value)) {
        node.include = true
        for (const f of node.fields) if (f.key in value) importValue(f.node, value[f.key])
        // Any keys the schema doesn't declare go into the additionalProperties map editor (when the
        // schema permits them); without one they're simply dropped from the form view.
        if (node.additional) {
          const known = new Set(node.fields.map(f => f.key))
          node.additional.entries = []
          for (const [k, val] of Object.entries(value)) {
            if (known.has(k)) continue
            const child = makeNode(node.additional.valueSchema, true, node.additional.valueSeen)
            importValue(child, val)
            node.additional.entries.push({ _key: nextId(), key: k, node: child })
          }
        }
      }
      break
    case 'array':
      node.include = value !== undefined
      if (node.primitive) {
        node.text = Array.isArray(value) ? value.map(v => v == null ? '' : String(v)).join('\n') : ''
        break
      }
      node.items = []
      if (Array.isArray(value)) {
        for (const el of value) {
          const child = makeNode(node.itemSchema, false, node.itemSeen)
          child._key = nextId()
          importValue(child, el)
          node.items.push(child)
        }
      }
      break
    case 'map':
      node.entries = []
      if (value && typeof value === 'object' && !Array.isArray(value)) {
        for (const [k, val] of Object.entries(value)) {
          const child = makeNode(node.valueSchema, true, node.valueSeen)
          importValue(child, val)
          node.entries.push({ _key: nextId(), key: k, node: child })
        }
      }
      break
    case 'variant':
      node.include = value !== undefined
      if (node.multi) {
        // Check each branch the incoming object overlaps (shares a property with) and import the
        // overlap into it. Lossy by nature — anyOf branches can overlap — but predictable.
        if (value && typeof value === 'object' && !Array.isArray(value)) {
          for (const v of node.variants) {
            const overlaps = ((v.child && v.child.fields) || []).some(f => f.key in value)
            v.include = overlaps
            if (overlaps) importValue(v.child, value)
          }
        }
        break
      }
      // With a discriminator, switch to the branch whose value matches the incoming payload.
      if (node.discriminator && value && typeof value === 'object') {
        const dv = value[node.discriminator]
        const idx = node.variants.findIndex(v => v.discValue != null && String(v.discValue) === String(dv))
        if (idx >= 0 && idx !== node.selected) selectVariant(node, idx)
      }
      importValue(node.child, value)
      break
  }
}

// --- non-blocking validation -----------------------------------------------

// Advisory warnings for the request-body form. Mirrors serializeNode's inclusion rules (only
// fields that would actually be sent are checked) and reports required-presence plus simple
// constraint violations. Never blocks sending — a request with a deliberately invalid/missing
// value must still be sendable (for endpoint-validation testing). Returns [{ path, message }].
export function collectBodyWarnings(node, path = '$') {
  const out = []
  if (!node) return out
  switch (node.kind) {
    case 'string': {
      const active = node.required || node.include
      if (node.required && (node.value == null || node.value === '')) { out.push({ path, message: 'required, but empty' }); break }
      if (active && node.value) {
        if (node.minLength != null && node.value.length < node.minLength) out.push({ path, message: `shorter than minLength ${node.minLength}` })
        if (node.maxLength != null && node.value.length > node.maxLength) out.push({ path, message: `longer than maxLength ${node.maxLength}` })
        if (node.pattern) { try { if (!new RegExp(node.pattern).test(node.value)) out.push({ path, message: `does not match pattern ${node.pattern}` }) } catch (e) { /* invalid spec pattern */ } }
      }
      break
    }
    case 'number': {
      const empty = node.value === '' || node.value == null
      if (node.required && empty) { out.push({ path, message: 'required, but empty' }); break }
      if (!empty) {
        const n = Number(node.value)
        if (Number.isNaN(n)) out.push({ path, message: 'not a number' })
        else {
          if (node.minimum != null && n < node.minimum) out.push({ path, message: `below minimum ${node.minimum}` })
          if (node.maximum != null && n > node.maximum) out.push({ path, message: `above maximum ${node.maximum}` })
          if (node.integer && !Number.isInteger(n)) out.push({ path, message: 'not an integer' })
        }
      }
      break
    }
    case 'enum': {
      const active = node.required || node.include
      if (node.required && (node.value == null || node.value === '')) out.push({ path, message: 'required, but no value chosen' })
      else if (active && node.value !== '' && node.value != null && Array.isArray(node.options) && !node.options.map(String).includes(String(node.value))) out.push({ path, message: 'value not in the allowed set' })
      break
    }
    case 'object':
      if (!node.required && !node.include) break
      for (const f of node.fields) out.push(...collectBodyWarnings(f.node, `${path}.${f.key}`))
      if (node.additional) node.additional.entries.forEach(e => { if (e.key) out.push(...collectBodyWarnings(e.node, `${path}.${e.key}`)) })
      break
    case 'array':
      if (!node.primitive) node.items.forEach((it, i) => out.push(...collectBodyWarnings(it, `${path}[${i}]`)))
      break
    case 'map':
      node.entries.forEach((e) => { if (e.key) out.push(...collectBodyWarnings(e.node, `${path}.${e.key}`)) })
      break
    case 'variant':
      if (!node.required && !node.include) break
      if (node.multi) {
        const checked = node.variants.filter(v => v.include)
        if (node.required && checked.length === 0) { out.push({ path, message: 'select at least one branch' }); break }
        for (const v of checked) out.push(...collectBodyWarnings(v.child, path))
      } else {
        out.push(...collectBodyWarnings(node.child, path))
      }
      break
    case 'file':
      if (node.required && (!node.files || node.files.length === 0)) out.push({ path, message: 'required, but no file chosen' })
      break
  }
  return out
}

// --- operations ------------------------------------------------------------

const METHODS = ['get', 'post', 'put', 'patch', 'delete', 'head', 'options']

export function collectOperations() {
  const ops = []
  const paths = (ROOT && ROOT.paths) || {}
  for (const [path, item] of Object.entries(paths)) {
    for (const method of METHODS) {
      const op = item[method]
      if (!op) continue
      ops.push({
        // x-* vendor extensions pass through untouched so extension seams (e.g. the response-body
        // transformer) can read an operation's own config off the operation object. Spread first so
        // the curated fields below win on the off chance an x- key ever collided.
        ...vendorExtensions(op),
        id: `${method.toUpperCase()} ${path}`,
        method: method.toUpperCase(),
        path,
        summary: op.summary || '',
        description: op.description || '',
        operationId: op.operationId || '',
        deprecated: !!op.deprecated,
        externalDocs: sanitizeExternalDocs(op.externalDocs),
        tags: op.tags && op.tags.length ? op.tags : ['default'],
        parameters: [...(item.parameters || []), ...(op.parameters || [])],
        requestBody: op.requestBody || null,
        responses: op.responses || {}
      })
    }
  }
  return ops
}

// The x-* vendor extensions of a spec node, as a plain object (empty when there are none). The core
// never interprets these — they're carried through verbatim so an extension can read its own config
// (see collectOperations and the response-body-transformer seam).
function vendorExtensions(node) {
  const out = {}
  if (node) for (const key of Object.keys(node)) if (key.startsWith('x-')) out[key] = node[key]
  return out
}

// Spec-supplied link targets (operation externalDocs.url, example externalValue) flow into <a href>;
// blank any whose scheme isn't safe (see config.js isSafeHref) so the templates' v-if-on-presence
// simply hides the link rather than rendering a javascript:/data: URL in the explorer's origin.
function safeHref(url) {
  return isSafeHref(url) ? url : ''
}

function sanitizeExternalDocs(ed) {
  if (!ed) return null
  return ed.url && !isSafeHref(ed.url) ? { ...ed, url: '' } : ed
}

// Normalizes the OpenAPI named-`examples` map (and the singular `example`) of any holder — a media
// type object or a Parameter object — into a flat list: [{ name, summary, description, value,
// externalValue }]. The named map wins; a lone singular `example` becomes a single "Example" entry.
// An entry may carry an `externalValue` (a URL) instead of an inline `value`. Returns [] when none.
export function namedExamples(holder) {
  if (!holder) return []
  const out = []
  if (holder.examples && typeof holder.examples === 'object') {
    for (const [name, ex] of Object.entries(holder.examples)) {
      if (!ex || typeof ex !== 'object') continue
      out.push({ name, summary: ex.summary || '', description: ex.description || '', value: ex.value, externalValue: safeHref(ex.externalValue) })
    }
  }
  if (!out.length && holder.example !== undefined) {
    out.push({ name: 'Example', summary: '', description: '', value: holder.example, externalValue: '' })
  }
  return out
}

// Converts an example value to the string a parameter/text field expects: arrays become newline-
// joined (matching the array textarea), objects JSON, scalars stringified, null/undefined empty.
export function exampleToField(value) {
  if (value == null) return ''
  if (Array.isArray(value)) return value.map(x => x == null ? '' : (typeof x === 'object' ? JSON.stringify(x) : String(x))).join('\n')
  if (typeof value === 'object') return JSON.stringify(value)
  return String(value)
}

// Maps a request-body media type to the editor/serialization strategy the explorer uses.
export function bodyKind(mediaType) {
  const m = (mediaType || '').toLowerCase()
  if (m === 'application/x-www-form-urlencoded') return 'form'
  if (m === 'multipart/form-data') return 'multipart'
  if (m === 'application/json' || m.endsWith('+json')) return 'json'
  return 'other'
}

// One entry per declared request-body media type: { mediaType, schema, required, kind, examples }.
// examples is the normalized named-examples list (see namedExamples). JSON is listed first so it
// stays the default when an operation offers several encodings.
export function requestBodyMediaTypes(operation) {
  const content = operation.requestBody && operation.requestBody.content
  if (!content) return []
  const required = !!operation.requestBody.required
  const types = Object.entries(content).map(([mediaType, mt]) => ({
    mediaType, schema: (mt && mt.schema) || {}, required, kind: bodyKind(mediaType), examples: namedExamples(mt)
  }))
  types.sort((a, b) => (a.kind === 'json' ? -1 : 0) - (b.kind === 'json' ? -1 : 0))
  return types
}

// Builds a multipart/form-data body (FormData) from the form node tree. File nodes contribute their
// selected File(s); other top-level fields are appended as text (objects/arrays as JSON). The browser
// adds the Content-Type with its boundary, so callers must not set that header themselves.
export function serializeMultipart(node) {
  const fd = new FormData()
  if (node && node.kind === 'object') for (const f of node.fields) appendPart(fd, f.key, f.node)
  return fd
}

function appendPart(fd, key, node) {
  if (node.kind === 'file') {
    if (!node.required && !node.include) return
    for (const file of (node.files || [])) fd.append(key, file, file.name)
    return
  }
  const v = serializeNode(node)
  if (v === OMIT) return
  if (v !== null && typeof v === 'object') fd.append(key, JSON.stringify(v))
  else fd.append(key, v == null ? '' : String(v))
}

// Builds an application/x-www-form-urlencoded body from the form node tree (top-level fields only;
// arrays repeat the key, objects are JSON-encoded). Returns a URLSearchParams.
export function serializeUrlEncoded(node) {
  const params = new URLSearchParams()
  if (node && node.kind === 'object') {
    for (const f of node.fields) {
      const v = serializeNode(f.node)
      if (v === OMIT) continue
      if (Array.isArray(v)) v.forEach(x => params.append(f.key, x == null ? '' : (typeof x === 'object' ? JSON.stringify(x) : String(x))))
      else if (v !== null && typeof v === 'object') params.append(f.key, JSON.stringify(v))
      else params.append(f.key, v == null ? '' : String(v))
    }
  }
  return params
}

// Returns one entry per declared response: { status, description, schema|null, headers|null,
// examples }. headers is a flat [{ name, description, schema }] for documentation; examples is the
// normalized named-examples list (see namedExamples) of the chosen media type.
export function getResponseSchemas(operation) {
  return Object.entries(operation.responses || {}).map(([status, resp]) => {
    const content = resp && resp.content
    const json = content && (content['application/json'] || content[Object.keys(content)[0]])
    const headers = resp && resp.headers
      ? Object.entries(resp.headers).map(([name, h]) => ({ name, description: (h && h.description) || '', schema: (h && h.schema) || null }))
      : null
    return { status, description: (resp && resp.description) || '', schema: json ? (json.schema || {}) : null, headers, examples: namedExamples(json) }
  })
}

// --- read-only schema documentation (type tree + example) ------------------

// Builds a descriptive tree for documentation: { typeLabel, description, required, enumValues, fields }.
// Arrays/maps of objects lift the inner object's fields so they render inline.
export function schemaTree(schema, seen = new Set()) {
  const d = deref(schema, seen)
  if (d.cyclic) return { typeLabel: '(recursive)' }
  const s = mergeAllOf(d.schema, d.seen)
  if (s.__unsupported) return { typeLabel: 'object', description: s.__reason }
  const description = s.description
  const deprecated = !!s.deprecated
  if (Array.isArray(s.enum)) return { typeLabel: 'enum', enumValues: s.enum, description, deprecated }

  if (Array.isArray(s.oneOf) || Array.isArray(s.anyOf)) {
    const keyword = Array.isArray(s.oneOf) ? 'oneOf' : 'anyOf'
    const subs = (s[keyword] || []).filter(sub => sub && sub.type !== 'null')
    const disc = s.discriminator || null
    const mappingByRef = {}
    if (disc && disc.mapping) for (const [val, ref] of Object.entries(disc.mapping)) mappingByRef[ref] = val
    const variants = subs.map((sub, i) => ({ label: variantLabel(sub, i, mappingByRef), node: schemaTree(sub, d.seen) }))
    return { typeLabel: keyword, description, deprecated, variants }
  }

  const type = normalizeType(s)
  const ap = s.additionalProperties
  const hasProps = s.properties && Object.keys(s.properties).length > 0

  if ((type === 'object' || (!type && ap !== undefined)) && !hasProps && ap !== undefined && ap !== false) {
    const v = schemaTree((ap === true || typeof ap !== 'object') ? { type: 'string' } : ap, d.seen)
    return { typeLabel: `map<${v.typeLabel}>`, description, deprecated, fields: v.fields || null }
  }
  if (type === 'object' || (!type && s.properties)) {
    const req = new Set(s.required || [])
    const fields = Object.entries(s.properties || {}).map(([k, ps]) => ({ name: k, required: req.has(k), node: schemaTree(ps, d.seen) }))
    const result = { typeLabel: 'object', description, deprecated, fields }
    // Document that extra free-form keys are allowed (fixed properties + additionalProperties).
    if (ap !== undefined && ap !== false) {
      const v = schemaTree((ap === true || typeof ap !== 'object') ? { type: 'string' } : ap, d.seen)
      result.additionalType = `map<${v.typeLabel}>`
    }
    return result
  }
  if (type === 'array' || (!type && s.items)) {
    const it = schemaTree(s.items || {}, d.seen)
    return { typeLabel: `array<${it.typeLabel}>`, description, deprecated, fields: it.fields || null, enumValues: it.enumValues }
  }
  if (type) return { typeLabel: s.format ? `${type} (${s.format})` : type, description, deprecated, constraints: constraintText(s, type) }
  return { typeLabel: 'any', description, deprecated }
}

// Generates a representative example instance from a schema (cycle-guarded).
export function schemaExample(schema, seen = new Set()) {
  const d = deref(schema, seen)
  if (d.cyclic) return null
  const s = mergeAllOf(d.schema, d.seen)
  if (s.__unsupported) return null
  if (s.example !== undefined) return s.example
  if (s.default !== undefined) return s.default
  if (Array.isArray(s.enum)) return s.enum[0]

  if (Array.isArray(s.oneOf) || Array.isArray(s.anyOf)) {
    const keyword = Array.isArray(s.oneOf) ? 'oneOf' : 'anyOf'
    const subs = (s[keyword] || []).filter(sub => sub && sub.type !== 'null')
    return subs.length ? schemaExample(subs[0], d.seen) : null
  }

  const type = normalizeType(s)
  const ap = s.additionalProperties
  const hasProps = s.properties && Object.keys(s.properties).length > 0

  if ((type === 'object' || (!type && ap !== undefined)) && !hasProps && ap !== undefined && ap !== false) {
    return { key: schemaExample((ap === true || typeof ap !== 'object') ? { type: 'string' } : ap, d.seen) }
  }
  if (type === 'object' || (!type && s.properties)) {
    const o = {}
    for (const [k, ps] of Object.entries(s.properties || {})) o[k] = schemaExample(ps, d.seen)
    return o
  }
  if (type === 'array' || (!type && s.items)) return [schemaExample(s.items || {}, d.seen)]
  if (type === 'string') return s.format || 'string'
  if (type === 'integer' || type === 'number') return 0
  if (type === 'boolean') return false
  return null
}
