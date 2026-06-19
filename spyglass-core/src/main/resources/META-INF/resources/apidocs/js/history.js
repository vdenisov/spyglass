// Self-managed recent-value history for input fields, surfaced through the themed <ComboBox>
// dropdown (Kibana-style). Replaces reliance on the browser's own autofill / native <datalist>,
// which is inconsistent across browsers, unstyleable, can't show the full list on demand, and
// can't be cleared or asserted in tests.
//
// One localStorage blob holds { [fieldKey]: [mostRecent, …] }, capped per field. Values are
// recorded on Send (params/body) and on Generate & apply (auth), so the dropdown reflects what was
// actually used, not every keystroke.

import { FIELD_HISTORY_KEY, loadJson, saveJson } from './storage.js'

const MAX_PER_FIELD = 10

// Key builders centralize the scheme. Params and body fields are operation-scoped (op.id is
// "<METHOD> <path>"); auth fields are global, since the auth form is shared across all operations —
// so a UID entered there is suggested everywhere.
export const paramKey = (opId, param) => `${opId}|p:${param.in}:${param.name}`
export const bodyFieldKey = (opId, fieldKey) => `${opId}|b:${fieldKey}`
export const authKey = (name) => `auth:${name}`

function readAll() {
  const all = loadJson(localStorage, FIELD_HISTORY_KEY, {})
  return all && typeof all === 'object' ? all : {}
}

// Most-recent-first list of distinct values previously recorded for this field.
export function getValues(fieldKey) {
  const list = readAll()[fieldKey]
  return Array.isArray(list) ? list : []
}

// Records a used value: trims, ignores blanks, dedupes (case-sensitive), moves it to the front,
// and caps the list. No-op for empty values so the dropdown never offers a blank entry.
export function recordValue(fieldKey, value) {
  const v = (value == null ? '' : String(value)).trim()
  if (!v) return
  const all = readAll()
  const prev = Array.isArray(all[fieldKey]) ? all[fieldKey] : []
  all[fieldKey] = [v, ...prev.filter(x => x !== v)].slice(0, MAX_PER_FIELD)
  saveJson(localStorage, FIELD_HISTORY_KEY, all)
}

// Removes a single remembered value for a field (the per-suggestion "✕"), dropping the field entry
// entirely once its last value is gone.
export function removeValue(fieldKey, value) {
  const all = readAll()
  const prev = all[fieldKey]
  if (!Array.isArray(prev)) return
  const next = prev.filter(x => x !== String(value))
  if (next.length) all[fieldKey] = next
  else delete all[fieldKey]
  saveJson(localStorage, FIELD_HISTORY_KEY, all)
}
