# The adapter contract

`spyglass-core` depends on nothing beyond "something serves these static files and points the app at an
OpenAPI document." That framework-neutrality is a design invariant: the core must never acquire a
Spring (or servlet, or reactive) dependency. Supporting another JVM framework (Quarkus, Micronaut,
Dropwizard, …) is therefore an additive **adapter**, not a rewrite.

This page states the contract in framework-neutral terms, then shows how the two Spring adapters
(`spyglass-spring-webmvc`, `spyglass-spring-webflux`) realize each part — so a new adapter for another
framework knows what to provide.

## What any adapter must provide

1. **Serve the core assets** at `/apidocs/**`, from `spyglass-core`'s
   `META-INF/resources/apidocs/**`, **and** front-end extension assets at `/spyglass-ext/**`, from
   `META-INF/resources/spyglass-ext/**`. Both are served with a revalidate-on-reuse cache policy
   (`Cache-Control: no-cache` + a content ETag, `Last-Modified` disabled) so a redeploy is picked up
   without a hard refresh. Because the classpath merges `spyglass-ext/` across jars, one handler enrols
   every extension's assets with no per-extension config.

2. **Make the OpenAPI document carry the Spyglass customizations** — register an `Authorization`
   header security scheme (and require it), set a default title when the host hasn't, and add the
   `x-service-name` `info` extension. This is *additive*: augment the document the framework's own
   OpenAPI generator produces; never replace it.

3. **Wire the friendly redirects.** `GET /`, `GET /apidocs`, and `GET /apidocs/` each redirect to
   `/apidocs/index.html` with **`302 Found`**. Mapping `/` makes the explorer the default API docs
   page — note it takes over `GET /`, so a host serving its own root `index.html` should reach the
   explorer via `/apidocs/` instead.

4. **Expose one activation surface** that wires the document customization and the redirects together,
   so a consumer activates the explorer with a single declaration.

5. **Honor the framework's docs-serving quirks** where they affect the front end — e.g. if the OpenAPI
   document can be served on a separate port, the front-end spec URL is supplied through `config.js`
   rather than hard-coded.

## How the Spring adapters realize it

- **Asset serving** registers a dedicated resource handler for each convention root (`/apidocs/**` and
  `/spyglass-ext/**`) carrying the no-cache + content-ETag policy (the shared, stack-neutral
  `ExplorerAssets` in `spyglass-spring-core` supplies it; the per-stack `ExplorerAssetHandlers` helper
  wires it). Boot would serve `META-INF/resources` on classpath presence alone, but its defaults send no
  `Cache-Control`, which lets browsers miss a redeploy — hence the explicit handlers. An extension on a
  non-conventional path applies the same policy in one call through that helper.
- **Document customization** is the stack-neutral `SpyglassOpenApiCustomizer` (in
  `spyglass-spring-core`), registered as a springdoc `OpenApiCustomizer` bean. It targets the
  springdoc-**common** SPI and the `io.swagger.v3.oas.models` model — both stable across
  servlet/reactive and across springdoc 2.x/3.x — so no per-stack or per-major variant is needed. On a
  non-springdoc framework, the equivalent is whatever hook that framework gives for post-processing its
  generated OpenAPI model.
- **Redirects** — servlet: `WebMvcConfigurer#addViewControllers` with
  `addRedirectViewController(...).setStatusCode(FOUND)`; reactive: a `RouterFunction` bean (not a second
  `WebFluxConfigurer`, so it can't clobber a host's global `/**` CORS).
- **Entry point** — both adapters name it `SpyglassConfiguration`.
- **Docs-serving quirk** — on reactive springdoc, `springdoc.use-management-port` may move the document
  to the management port; the spec URL is then set in `config.js`, not adapter code. The servlet
  adapter has no equivalent.

## What an adapter must NOT do

- **Do not give the core a framework dependency.** The adapter is the only stack-aware layer; the core
  stays pure static assets.
- **Do not replace the generated OpenAPI document.** The customization is *additive*, so it composes
  with the framework's defaults and any host customization. Where a host already defines its own
  document, augment it.
- **Do not carry consumer-specific code.** Identity minting, platform headers, deep-link targets, and
  the like belong in an extension, never in an adapter or the core.

## Activation styles

The adapters in this project use the explicit `@Import` style and ship no auto-registration metadata,
so nothing activates implicitly. An extension that wants zero-code activation layers an auto-registering
shim (an `AutoConfiguration.imports` entry pointing at the entry point) on top — keeping the
opt-in-by-import library and the auto-on variant cleanly separated.
