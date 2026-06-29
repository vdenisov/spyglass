// Host override hook — shipped as a no-op. This file lets a host that serves the explorer's assets
// as-is (a static asset server that can't template or edit index.html, and won't fork the jar) set
// window.SPYGLASS_CONFIG without touching any other file: it overrides just THIS one served asset.
//
// index.html loads it as a sourced, classic <script> in <head>, ahead of the inline theme script and
// of the app module. A classic script runs synchronously during HTML parse — before any type="module"
// script (modules always defer) — so anything set here is in place by the time config.js reads
// window.SPYGLASS_CONFIG at module-eval time, and ahead of the no-flash theme script's storage-namespace
// resolution. Being sourced (not inline), it's covered by `script-src 'self'`: no CSP 'unsafe-inline'.
//
// As shipped this file sets nothing, so resolution is identical to the built-in defaults. To point the
// explorer at a non-default spec (or retune any other setting), replace this file with one that assigns
// the global, e.g. for a service hosting its OpenAPI document at /myservice/openapi.json:
//
//   window.SPYGLASS_CONFIG = {
//     specUrl: '/myservice/openapi.json'
//     // storageNamespace, extensions, updateCheck, requestLog — see docs/configuration.md
//   }
//
// Operator-set values here are trusted and may point anywhere (unlike the same-origin-restricted ?spec=
// query param). The query param still wins when present, so a deployed default plus an optional per-load
// override both work.
