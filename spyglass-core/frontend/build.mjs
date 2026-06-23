// Bundles entry.mjs into the API explorer's vendored CodeMirror editor and writes a
// third-party license report. Run via `npm run build` (see build.sh for the Docker wrapper).
import { build } from 'esbuild'
import { readFileSync, writeFileSync, readdirSync, existsSync } from 'node:fs'
import { join, dirname } from 'node:path'
import { fileURLToPath } from 'node:url'

const here = dirname(fileURLToPath(import.meta.url))
const vendorDir = join(here, '..', 'src', 'main', 'resources', 'META-INF', 'resources', 'apidocs', 'vendor')
const outfile = join(vendorDir, 'codemirror.bundle.js')

// codemirror-json-schema's tooltip renderer (dist/utils/markdown.js) statically imports
// shiki + markdown-it for syntax-highlighted markdown tooltips the explorer doesn't need.
// Redirect that one internal import to a stub backed by `marked` (already vendored by the
// explorer), dropping the shiki engine and ~30 transitive packages from the bundle. Breaks
// loudly (build error) if the upstream module path changes on a version bump — see README.
const stubShikiMarkdown = {
  name: 'stub-cmjs-markdown',
  setup(b) {
    const stub = join(here, 'shims', 'markdown-marked.mjs')
    b.onResolve({ filter: /utils\/markdown$/ }, (args) =>
      args.importer.includes('codemirror-json-schema') ? { path: stub } : null)
  }
}

// codemirror-json-schema renders markdown for hover and property-name autocomplete docs, but
// passes enum/const VALUE completion descriptions as a raw string, which CodeMirror shows as
// plain text (literal markdown). Rewrite those two sites to the same renderMarkdown path, with a
// null-guard so blank descriptions produce no tooltip. Asserts the exact occurrence count and
// fails the build if the upstream source changes — see frontend/README.md.
const patchValueCompletionInfo = {
  name: 'patch-cmjs-value-info',
  setup(b) {
    const FIND = '{ info: schema.description }'
    const REPLACE = '{ info: schema.description ? () => el("div", { inner: renderMarkdown(schema.description) }) : void 0 }'
    const EXPECTED = 2
    b.onLoad({ filter: /codemirror-json-schema[\\/]dist[\\/]features[\\/]completion\.js$/ }, (args) => {
      const src = readFileSync(args.path, 'utf8')
      const count = src.split(FIND).length - 1
      if (count !== EXPECTED) {
        throw new Error(`patch-cmjs-value-info: expected ${EXPECTED} occurrences of ${JSON.stringify(FIND)} ` +
          `in completion.js, found ${count}. codemirror-json-schema source changed — re-check this patch.`)
      }
      return { contents: src.split(FIND).join(REPLACE), loader: 'js' }
    })
  }
}

// --- bundle ----------------------------------------------------------------
const result = await build({
  entryPoints: [join(here, 'entry.mjs')],
  bundle: true,
  format: 'esm',
  target: 'es2020',
  minify: true,
  plugins: [stubShikiMarkdown, patchValueCompletionInfo],
  // marked is provided at runtime by the explorer's importmap; don't inline a second copy.
  external: ['marked'],
  // Keep upstream license banners in the served file; THIRD-PARTY-NOTICES.txt holds the full set.
  legalComments: 'eof',
  metafile: true,
  outfile,
  logLevel: 'info'
})
console.log('Bundle written:', outfile)

// --- third-party license report (only packages actually in the bundle) -----
// Drive this off esbuild's metafile, not the install tree, so tree-shaken modules
// (e.g. the unused YAML/JSON5 code paths) aren't falsely attributed.
const NM = 'node_modules/'
function pkgRoot(input) {
  const idx = input.lastIndexOf(NM)
  if (idx === -1) return null
  const parts = input.slice(idx + NM.length).split('/')
  const name = parts[0].startsWith('@') ? `${parts[0]}/${parts[1]}` : parts[0]
  return { name, dir: join(here, input.slice(0, idx + NM.length), name) }
}

// SPDX labels for packages whose package.json omits a `license` field but ship a
// LICENSE file we have read and identified. The full license text is still emitted
// in the report body below; this only corrects the one-line summary label.
const LICENSE_OVERRIDES = {
  // package.json has `license: null`; LICENSE file is verbatim MIT text
  // ((c) 2013 Odysseas Tsatalos and oDesk Corporation).
  'valid-url@1.0.9': 'MIT'
}

const LICENSE_FILE = /^(licen[sc]e|copying|notice)/i
const pkgs = new Map()
for (const input of Object.keys(result.metafile.inputs)) {
  const root = pkgRoot(input)
  if (!root) continue
  const pj = join(root.dir, 'package.json')
  if (!existsSync(pj)) continue
  const meta = JSON.parse(readFileSync(pj, 'utf8'))
  if (!meta.name) continue
  const key = `${meta.name}@${meta.version}`
  if (pkgs.has(key)) continue
  const license = LICENSE_OVERRIDES[key] || (typeof meta.license === 'string'
    ? meta.license
    : (meta.license && meta.license.type) || (Array.isArray(meta.licenses) && meta.licenses.map(l => l.type).join(' OR ')) || 'UNKNOWN')
  let text = ''
  for (const f of readdirSync(root.dir)) {
    if (LICENSE_FILE.test(f)) { text = readFileSync(join(root.dir, f), 'utf8'); break }
  }
  pkgs.set(key, { license, text })
}

// Fail loudly if an override key no longer matches a bundled package — e.g. the dependency bumped its
// version (so `valid-url@1.0.9` becomes `valid-url@1.0.10`) or was dropped. Without this, a stale key is
// silently ignored and the label falls through to UNKNOWN in the notices. Forces a conscious re-check of
// the override (re-confirm the license, bump the key, or remove it), matching the fail-on-drift stance
// of the other interventions in this script.
for (const key of Object.keys(LICENSE_OVERRIDES)) {
  if (!pkgs.has(key)) {
    throw new Error(`LICENSE_OVERRIDES has a stale key "${key}": no bundled package matches it. ` +
      `The dependency likely changed version or was dropped — re-confirm its license, then update or ` +
      `remove the override.`)
  }
}

// --- hand-vendored runtime libraries (served via the import map, not bundled here) ----------
// Vue and marked are vendored directly as ESM under apidocs/vendor/ and loaded through the HTML
// import map, so they never pass through esbuild and are absent from the metafile above. They ship
// in the spyglass-core jar, so attribute them here too. The version is read from each file's banner
// so the notice cannot silently drift from the actually-vendored file; a missing banner fails the
// build loudly (like the other interventions in this script).
const mitText = (copyright) => `MIT License

${copyright}

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.`

const VENDORED_LIBS = [
  {
    name: 'vue', file: 'vue.esm-browser.prod.js', versionRe: /vue v([\d.]+)/, license: 'MIT',
    copyright: '(c) 2018-present Yuxi (Evan) You and Vue contributors'
  },
  {
    name: 'marked', file: 'marked.esm.js', versionRe: /marked v([\d.]+)/, license: 'MIT',
    copyright: 'Copyright (c) 2018-2026, MarkedJS. (MIT License)\n' +
      'Copyright (c) 2011-2018, Christopher Jeffrey. (MIT License)'
  }
]
const vendored = VENDORED_LIBS.map((lib) => {
  const banner = readFileSync(join(vendorDir, lib.file), 'utf8').slice(0, 4000)
  const m = banner.match(lib.versionRe)
  if (!m) {
    throw new Error(`THIRD-PARTY notices: no version found in ${lib.file} banner — ` +
      `re-check the vendored file or ${lib.versionRe}.`)
  }
  return { key: `${lib.name}@${m[1]}`, license: lib.license, text: mitText(lib.copyright) }
})

// --- assemble the report ----------------------------------------------------
// Two groups: the import-map-vendored runtime libs, then the npm modules actually bundled into
// codemirror.bundle.js. Both ship in the spyglass-core jar.
const out = [
  'Third-party licenses for the Spyglass front-end vendored code',
  'Generated by build.mjs (frontend/). Do not edit by hand — re-run frontend/build.sh.',
  '',
  'Covers (1) the runtime libraries vendored as ESM and served via the import map, and (2) every',
  "npm module actually bundled into codemirror.bundle.js (driven off esbuild's metafile, so",
  'tree-shaken modules are not falsely attributed).',
  ''
]
out.push('VENDORED RUNTIME LIBRARIES (served via the import map)')
for (const v of vendored) out.push(`  ${v.key}  —  ${v.license}`)
out.push('', `BUNDLED INTO codemirror.bundle.js (${pkgs.size} packages)`)
for (const [key, v] of [...pkgs].sort((a, b) => a[0].localeCompare(b[0]))) {
  out.push(`  ${key}  —  ${v.license}`)
}
out.push('')
for (const v of vendored) {
  out.push('='.repeat(80), `${v.key}  (${v.license})`, '='.repeat(80), v.text.trim(), '')
}
for (const [key, v] of [...pkgs].sort((a, b) => a[0].localeCompare(b[0]))) {
  out.push('='.repeat(80), `${key}  (${v.license})`, '='.repeat(80),
    v.text ? v.text.trim() : '(no license text file shipped in the package; see SPDX id above)', '')
}
writeFileSync(join(here, 'THIRD-PARTY-NOTICES.txt'), out.join('\n'))
console.log(`License report written: THIRD-PARTY-NOTICES.txt (${vendored.length} vendored + ${pkgs.size} bundled)`)
