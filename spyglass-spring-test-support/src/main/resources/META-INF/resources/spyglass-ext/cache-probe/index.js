// Minimal extension fixture for the asset-caching specs (ExplorerAssetCachingSpecBase). Shipped by the
// shared test-support jar so it lands on both adapters' test classpaths under /spyglass-ext/cache-probe/.
// Distinct from the webmvc-only `probe` fixture used by the browser seam specs. No behaviour is exercised;
// the specs only assert how this asset is *served* (no-cache + content ETag + no Last-Modified).
export function register() {}
