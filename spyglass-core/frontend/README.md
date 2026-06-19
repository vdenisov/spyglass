# Frontend build (Spyglass editor bundle)

The Spyglass explorer under `spyglass-core/src/main/resources/META-INF/resources/apidocs/` is a no-build, vendored-ESM
app (Vue and marked ship as prebuilt files, loaded through an importmap). The one piece that
*can't* be hand-vendored is CodeMirror 6: it spans ~15 interdependent packages, and loading them
separately from a CDN pulls in multiple copies of `@codemirror/state`, which breaks CodeMirror's
internal `instanceof` checks.

This folder bundles CodeMirror + the JSON-schema extension into a single self-contained ESM file
with exactly one copy of each shared package. The output is committed to the repo and served as a
normal static asset; this build runs only when the editor's dependencies change.

## What it produces

| Output | Purpose |
| --- | --- |
| `../src/main/resources/META-INF/resources/apidocs/vendor/codemirror.bundle.js` | The served bundle (imported via the `codemirror-bundle` importmap entry). |
| `THIRD-PARTY-NOTICES.txt` | Attribution for every production dependency in the bundle. |

Both are committed. Regenerate them only when changing versions in `package.json`.

The bundle keeps `marked` external (the explorer already vendors it) and imports it at runtime via
the `marked` importmap entry — so `codemirror.bundle.js` must be loaded in a page whose importmap
provides `marked` (the Spyglass explorer's `index.html` does).

`build.mjs` applies two build-time interventions to `codemirror-json-schema` (both documented in
that file). Each **fails the build loudly** if the upstream source changes, so a version bump can't
silently regress them:

- **Markdown renderer** — its shiki + markdown-it tooltip renderer is swapped for a `marked`-backed
  stub (`shims/markdown-marked.mjs`), dropping the shiki engine (~30 packages) from the bundle.
- **Value-completion docs** — enum/const value completions pass their description as a raw string
  (rendered as plain text); a patch routes them through the same markdown renderer as hover and
  property-name docs, with a null-guard for blank descriptions.

## How to build

### Option A — Docker (no local Node needed; this is how the repo is normally built)

```bash
frontend/build.sh
```

Runs `npm ci` + the bundle + `npm audit` inside `node:20-alpine`. Requires Docker only.

### Option B — local Node (Node 20+)

```bash
cd frontend
npm ci
npm run build
npm audit --omit=dev   # optional supply-chain check
```

`npm ci` installs the exact versions in `package-lock.json`, so the build is reproducible:
the same lockfile + same `esbuild` version yields a byte-identical bundle.

## Updating dependencies

1. Edit versions in `package.json`.
2. Run `npm install` (updates `package-lock.json`) — or `frontend/build.sh` after deleting the lock.
3. Run the build (Option A or B).
4. Review `THIRD-PARTY-NOTICES.txt` for any new or non-permissive license, and check `npm audit`
   output for new advisories.
5. Commit the changed `package.json`, `package-lock.json`, `codemirror.bundle.js`, and
   `THIRD-PARTY-NOTICES.txt` together.
6. Run the explorer's Playwright specs (the `*AE` tests) against the new bundle — they exercise
   the editor's schema validation and autocomplete end-to-end.

## Editor / schema-engine validation

`codemirror-json-schema` validates request bodies in the Raw-JSON editor against the document's
OpenAPI 3.1 schemas. `$ref` resolution (via hoisting the `components` namespace onto the body schema),
`additionalProperties`, and `anyOf` are handled with no false positives — `anyOf` is covered by the
`ComposedSchemaValidationAE` browser spec (a body matching no branch is flagged; one matching a branch
is accepted).

Known limitation: a **discriminated `oneOf`** — particularly the cyclic "base + variant `allOf`" shape
— is **not enforced by the editor's validator**: it won't flag branch violations. The schema-driven
**form** handles `oneOf`/`discriminator` through its variant selector; the Raw-JSON editor is permissive
here (it won't wrongly block a valid body, but it won't catch an invalid one either). Validate such
bodies via the form, or rely on server-side validation. `["type","null"]` (3.1 nullable) arrays are
likewise not exercised by the specs — re-check if you depend on them.

## Files

| File | Role |
| --- | --- |
| `entry.mjs` | Bundle entry — re-exports the exact symbols `JsonEditor.js` imports. |
| `build.mjs` | esbuild invocation + license-report generator. |
| `build.sh` | Docker wrapper around `npm ci && node build.mjs && npm audit`. |
| `package.json` / `package-lock.json` | Pinned dependency set (source of truth for the bundle). |
