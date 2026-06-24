package org.plukh.spyglass.spring.core;

import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.http.CacheControl;
import org.springframework.util.DigestUtils;

import java.io.IOException;
import java.io.InputStream;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/**
 * Shared serving policy for the explorer's static assets, applied identically by the servlet and reactive
 * adapters so both expose the same cache behaviour for {@code /apidocs/**}.
 *
 * <p><strong>Why this exists.</strong> The explorer is a hand-written, non-bundled ES-module graph: every
 * {@code import} is its own request and no filename carries a content hash (there is no front-end build
 * step to inject one, and Spring's {@code VersionResourceResolver} cannot rewrite URLs inside static
 * {@code import} statements). Served with Spring's defaults the assets carry {@code Last-Modified} but no
 * {@code Cache-Control}, so browsers apply heuristic freshness and skip revalidation entirely — a redeploy
 * then goes unnoticed until a manual hard refresh.
 *
 * <p>The fix is to serve each asset with {@link #cacheControl() Cache-Control: no-cache} (revalidate
 * before reuse) and a {@link #etagGenerator() content-based ETag}, with {@code Last-Modified} disabled by
 * the caller. {@code no-cache} forces the browser to revalidate; the content ETag then yields a cheap
 * {@code 304} when the bytes are unchanged and fresh content the moment they differ — no hard refresh.
 *
 * <p>The ETag is intentionally content-derived rather than timestamp-derived: the demo ships as a
 * reproducible-build fat jar ({@code project.build.outputTimestamp} pins every entry's
 * {@code Last-Modified}), so a timestamp validator would report {@code 304} even after the JS changed.
 * Hashing the bytes is immune to that, which is also why the caller disables {@code Last-Modified}
 * outright rather than relying on the ETag merely out-ranking it.
 */
public final class ExplorerAssets {

    /** URL path pattern under which the explorer's assets are served. */
    public static final String PATH_PATTERN = "/apidocs/**";

    /** Classpath directory holding the committed assets (shipped by {@code spyglass-core}). */
    public static final String RESOURCE_BASE = "META-INF/resources/apidocs/";

    /** {@link #RESOURCE_BASE} as a {@code classpath:} location string, for the servlet resource registry. */
    public static final String CLASSPATH_LOCATION = "classpath:/" + RESOURCE_BASE;

    /** The explorer's static entry point — the target the friendly-path redirects resolve to. */
    public static final String ENTRY_POINT = "/apidocs/index.html";

    /**
     * Memoised content ETags, keyed by asset identity ({@link Resource#getDescription()}) and holding the
     * hash alongside the last-modified time it was computed for. The assets are immutable within a JVM run
     * (served from a fat jar in production), so this hashes each one once rather than on every
     * revalidation. The stored last-modified time is a local cache buster — unrelated to the suppressed
     * {@code Last-Modified} HTTP validator — so that an edited file under exploded classes in development
     * is re-hashed, keeping the live-refresh loop intact. Keying on identity and <em>overwriting</em> on a
     * change (rather than keying on identity+time and accumulating) bounds the map to the small, fixed
     * asset count even across repeated edits.
     */
    private static final Map<String, CachedEtag> ETAG_CACHE = new ConcurrentHashMap<>();

    private ExplorerAssets() {
    }

    /**
     * The asset location as a {@link Resource}, for the reactive adapter (which configures its handler
     * with {@code Resource} locations rather than the servlet registry's location strings).
     *
     * @return the classpath asset directory
     */
    public static ClassPathResource location() {
        return new ClassPathResource(RESOURCE_BASE);
    }

    /**
     * {@code Cache-Control: no-cache} — the response stays cacheable, but the browser must revalidate it
     * (against the ETag) before every reuse.
     *
     * @return the cache-control policy for explorer assets
     */
    public static CacheControl cacheControl() {
        return CacheControl.noCache();
    }

    /**
     * A content-based ETag generator: the MD5 of the asset's bytes, so the validator changes if and only
     * if the content does. The hash is {@link #ETAG_CACHE memoised} per asset (re-hashed only when the
     * resource's last-modified time changes) so it isn't recomputed on every revalidation. Returns
     * {@code null} when the resource can't be read, which simply omits the ETag for that response (it is
     * then re-fetched, and not memoised, rather than risk being served stale).
     *
     * @return the ETag generator function
     */
    public static Function<Resource, String> etagGenerator() {
        return resource -> {
            long lastModified;
            try {
                lastModified = resource.lastModified();
            } catch (IOException ex) {
                // Can't stat the resource for a cache key; hash it directly (uncached) this time.
                return hash(resource);
            }
            CachedEtag cached = ETAG_CACHE.get(resource.getDescription());
            if (cached != null && cached.lastModified() == lastModified) {
                return cached.hash();
            }
            String hash = hash(resource);
            // Overwrite (keyed on identity, not identity+time) so the map stays bounded to one entry per
            // asset even across repeated dev edits. Don't memoise a failed read, so it's retried next time.
            if (hash != null) {
                ETAG_CACHE.put(resource.getDescription(), new CachedEtag(lastModified, hash));
            }
            return hash;
        };
    }

    private static String hash(Resource resource) {
        try (InputStream in = resource.getInputStream()) {
            return DigestUtils.md5DigestAsHex(in);
        } catch (IOException ex) {
            return null;
        }
    }

    /** A memoised ETag: the content {@code hash} and the {@code lastModified} time it was computed for. */
    private record CachedEtag(long lastModified, String hash) {
    }
}
