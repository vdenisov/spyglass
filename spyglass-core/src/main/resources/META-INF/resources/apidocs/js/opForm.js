// Per-operation request-FORM snapshot, persisted to localStorage so a half-filled request survives a
// reload (and is restored when returning to an operation). Keyed by op.id ("<METHOD> <path>").
//
// Distinct from history.js: that is the capped recent-VALUES autocomplete store (a list per field);
// this is the single CURRENT snapshot per operation (params, media type, Form/Raw mode, the raw text,
// and the body as JSON). On reload the form is rebuilt from the schema and the saved JSON body is
// re-applied via spec.js `importValue` — the same translation the Form/Raw toggle uses — so nested
// objects, arrays, maps and oneOf/anyOf selections restore to whatever fidelity that path supports.
//
// Responses are deliberately NOT stored here: they can be large or sensitive, so the explorer keeps
// only the latest response per operation in memory (see App.js opStates), never on disk.

import { OP_FORM_KEY, loadJson, saveJson } from './storage.js'

function readAll() {
  const all = loadJson(localStorage, OP_FORM_KEY, {})
  return all && typeof all === 'object' ? all : {}
}

function writeAll(all) {
  saveJson(localStorage, OP_FORM_KEY, all)
}

// The saved snapshot for an operation, or null if none.
export function loadForm(opId) {
  const snap = readAll()[opId]
  return snap && typeof snap === 'object' ? snap : null
}

export function saveForm(opId, snapshot) {
  const all = readAll()
  all[opId] = snapshot
  writeAll(all)
}

// Drops one operation's snapshot (its Reset button), leaving the others intact.
export function removeForm(opId) {
  const all = readAll()
  if (opId in all) {
    delete all[opId]
    writeAll(all)
  }
}
