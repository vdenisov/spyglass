# Contributing to Spyglass

Thanks for your interest in Spyglass. This guide covers building, testing, and the project invariants
contributors are expected to keep.

## Prerequisites

- **JDK 21+** (the published artifacts compile to Java 21 bytecode).
- **Maven 3.9+**.
- For the front-end editor bundle only: **Docker** (or Node 20+). Not needed for ordinary Java work.

## Build

```bash
mvn install
```

This builds the whole reactor and runs the unit/slice specs. Browser specs are excluded by default
(see below).

### The two build legs

The springdoc 2.x/3.x · Boot 3.5/4 compatibility matrix is verified by two legs:

| Leg | Command | Stack |
| --- | --- | --- |
| default | `mvn install` | Boot 3.5 / springdoc 2.x |
| `boot4` | `mvn -Pboot4 install` | Boot 4.1 / springdoc 3.x |

The `boot4` profile only flips the two BOM-import versions; everything downstream is BOM-managed.
Both legs are run in CI (`.github/workflows/ci.yml`). A change to adapter or core code should pass
both.

## Tests

- **Unit / slice specs** (Spock) run in the default build (`*Spec` / `*Test`).
- **Browser specs** are named `*AE` (short for "API Explorer") and drive the explorer with
  Playwright. The `*AE` naming keeps them out of the default surefire/failsafe patterns; run them with:

  ```bash
  mvn verify -Papidocs-tests            # default leg
  mvn -Pboot4 verify -Papidocs-tests    # boot4 leg
  ```

  Playwright is an ordinary test dependency, so the specs are IDE-runnable. They need the Chromium
  browser installed once (the CI workflow installs it via the bundled Playwright CLI).

## The front-end editor bundle

The explorer is a no-build vendored-ESM app — **consumers and most contributors never run a
front-end build.** The one exception is the CodeMirror 6 + `codemirror-json-schema` bundle, which
cannot be hand-vendored. It is committed; rebuild it only when the editor's dependencies change:

```bash
frontend/build.sh        # Docker: npm ci + bundle + npm audit, no local Node
# or, with local Node 20+:
cd frontend && npm ci && npm run build && npm audit --omit=dev
```

The same lockfile + esbuild version yields a **byte-identical** bundle. Two documented build-time
interventions on `codemirror-json-schema` each **fail the build loudly** on upstream drift — do not
work around a failure; update the intervention. See `spyglass-core/frontend/README.md` for details and
for keeping `THIRD-PARTY-NOTICES.txt` current.

## Project invariants

- **The core carries no consumer-specific coupling.** `spyglass-core` and the Spring adapters must stay
  free of any specific consumer's endpoints, headers, identity schemes, or package names — those plug in
  through the [extension seam](docs/extension-seam.md). When in doubt, grep your change for
  consumer-specific terms before submitting.
- **The customizer is additive.** Never replace the `OpenAPI` bean; the `OpenApiCustomizer` composes
  with springdoc defaults and host customization. See the [adapter contract](docs/adapter-contract.md).
- **Don't break the public surface lightly.** The `@Import`/auto-registration entry points, the
  `config.js` precedence chain, and the `x-*` extension names are the consumer contract.

## Line endings & encoding

Use **Unix (LF)** line endings and **UTF-8** for all text files, including generated code, unless a
`.gitattributes` rule says otherwise.

## Pull requests

- Keep changes focused; match the surrounding code's style and comment density.
- Run both build legs (and `-Papidocs-tests` if you touched the explorer or an adapter) before
  submitting.
- Update the relevant `docs/` page when you change configuration, the extension seam, or the adapter
  contract.

## Releasing

The dependency `<version>` shown in `README.md` and `docs/` is kept in sync with the build
automatically: a `validate`-phase step under the `release` profile rewrites the `org.plukh.spyglass`
dependency version in those files from `${project.version}`, so the docs never drift from the released
coordinates. You don't bump the version in the docs by hand — bump the project version (the parent
pom), then run the release build.
