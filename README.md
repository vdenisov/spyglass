# Spyglass

**An embeddable, no-build OpenAPI explorer for Spring Boot.**

Spyglass renders a service's OpenAPI 3.x document as browsable, executable API documentation and ships
as a normal jar. Add one dependency and it replaces Swagger UI as your service's API docs page, served
from your own origin — no npm, no bundler, no build step.

## Why

Swagger UI is heavy, dated, and awkward to embed cleanly. The alternative — hand-rolling yet another
bundler-driven single-page app per service — is a lot of machinery for "let me try this endpoint."
Spyglass aims for the middle: a modern, lightweight explorer that's **vendored as static ESM** (Vue,
`marked`, and a pre-built CodeMirror bundle) and served straight from the classpath. The consuming
service just adds a dependency.

It is deliberately scoped to the **one** service it's embedded in — it documents and exercises that
service's own API, not a fleet. No proxy, no gateway, no service catalog.

## What you get

- Operation browsing with a filterable, keyboard-navigable sidebar; deprecated-operation indicators.
- A schema-driven request-body form: objects, arrays, enums, `oneOf`/`anyOf`/`discriminator` variant
  selectors, and non-JSON bodies (`multipart/form-data`, `application/x-www-form-urlencoded`).
- A Raw-JSON editor (CodeMirror 6) with live schema validation; required-field warnings that don't
  block sending.
- Same-origin "try it out", content-type-aware response rendering, named examples, request history,
  persisted UI state, `curl` generation, markdown descriptions, and light/dark theming.
- A documented front-end **extension seam** so an organization can contribute its own UI (auth panels,
  header presets) without forking the core.

## Quick start (servlet / Spring MVC)

Add the adapter (and a springdoc starter, if your service doesn't already have one):

```xml
<dependency>
  <groupId>org.plukh.spyglass</groupId>
  <artifactId>spyglass-spring-webmvc</artifactId>
  <version>0.1.0-SNAPSHOT</version>
</dependency>
```

> Pre-1.0: not yet on Maven Central. Build and install locally with `mvn install`, then depend on the
> `0.1.0-SNAPSHOT` artifacts.

Activate it with a single import:

```java
@Configuration
@Import(org.plukh.spyglass.spring.webmvc.SpyglassConfiguration.class)
class ApiDocsConfig {}
```

springdoc generates `/v3/api-docs` from your controllers; Spyglass serves the explorer at **`/apidocs`**.

## Try it

A runnable, dependency-free showcase lives in `spyglass-demo`:

```bash
mvn -pl spyglass-demo spring-boot:run
# then open http://localhost:8080/apidocs
```

Its toy endpoints exercise every front-end feature: `oneOf`/`anyOf`/`discriminator` bodies, named
examples, multipart/urlencoded uploads, image/binary responses, and a deprecated operation.

## Modules

| Module | What it is |
| --- | --- |
| `spyglass-core` | Framework-neutral front end: the static Vue/ESM app + CodeMirror bundle. No server code. |
| `spyglass-spring-core` | Stack-neutral Spring wiring: the additive springdoc `OpenApiCustomizer`. |
| `spyglass-spring-webmvc` | The servlet (Spring MVC) adapter: the `SpyglassConfiguration` entry point + friendly redirects. |
| `spyglass-demo` | A runnable showcase app (above). |

## Configuration & extension

The front end resolves its runtime config through a fixed precedence chain — URL query parameter →
`window.SPYGLASS_CONFIG` → built-in defaults — covering the spec URL, a `localStorage` namespace, and
the extension-module list. Extensions are ESM modules exporting `register(api)`; the explorer discovers
them via `?ext=`, `window.SPYGLASS_CONFIG.extensions`, or the spec's `x-spyglass-extensions` `info`
extension, and loads them into the same app instance.

## Compatibility

| | Supported now | On the roadmap |
| --- | --- | --- |
| Java | 21+ | |
| Spring Boot | 3.5 | 4.x |
| springdoc | 2.x | 3.x |
| Web stack | servlet (MVC) | reactive (WebFlux) |

## Status

Pre-1.0 and evolving: the public surface (entry points, properties, `config.js` precedence, the `x-*`
extension names) may still change before 1.0.

## License

[MIT](LICENSE). The vendored front-end code — Vue, `marked`, and the CodeMirror bundle's
dependencies — is attributed in
[`spyglass-core/frontend/THIRD-PARTY-NOTICES.txt`](spyglass-core/frontend/THIRD-PARTY-NOTICES.txt),
which is also shipped inside the `spyglass-core` jar under `META-INF/`.
