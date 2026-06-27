package org.plukh.spyglass.test

import org.springframework.beans.factory.annotation.Value
import spock.lang.Specification

/**
 * Shared base for the explorer's asset-caching specs across every Spring adapter. Drives the running app
 * over real HTTP (a plain {@link HttpURLConnection}, no browser) and asserts the revalidate-on-reuse cache
 * policy applied to the explorer's static assets: {@code Cache-Control: no-cache} forces revalidation, a
 * content ETag yields a cheap {@code 304} when the bytes are unchanged, and {@code Last-Modified} is
 * suppressed (it would be pinned by the reproducible-build jar). See {@code ExplorerAssets} for the why.
 *
 * <p>Like {@link ExplorerBrowserSpecBase} this base is <strong>stack-neutral</strong> and
 * <strong>Boot-version-neutral</strong>: it carries no {@code @SpringBootTest} and references no test
 * application, so each concrete subclass supplies its own boot context (servlet or reactive). It reads the
 * random port from the {@code local.server.port} property, set by the test framework on every Boot version.
 * Asset URLs are asserted as black-box literals (the paths a browser would request), not against
 * production constants, so the spec verifies the wired-up HTTP behaviour end to end.
 */
abstract class ExplorerAssetCachingSpecBase extends Specification {

    @Value('${local.server.port}')
    int port

    def "explorer assets are served with no-cache and a weak content ETag, but no Last-Modified"() {
        when:
        def conn = get('/apidocs/js/app.js')

        then:
        conn.responseCode == 200
        conn.getHeaderField('Cache-Control') == 'no-cache'
        // A weak validator (W/"...") on purpose: it still drives revalidation, but lets a fronting server
        // gzip the asset (a strong ETag would block compression). See ExplorerAssets for the why.
        conn.getHeaderField('ETag') ==~ /W\/".+"/
        conn.getHeaderField('Last-Modified') == null
    }

    def "a conditional request matching the ETag revalidates to 304 Not Modified"() {
        given: 'the ETag the server advertises for the asset'
        def etag = get('/apidocs/js/app.js').getHeaderField('ETag')

        when: 'the browser re-requests with that ETag'
        def conn = get('/apidocs/js/app.js', etag)

        then: 'the asset is unchanged, so it revalidates to 304'
        conn.responseCode == 304
    }

    def "the friendly /apidocs/ path still redirects to the static entry point"() {
        when:
        def conn = get('/apidocs/')

        then:
        conn.responseCode == 302
        conn.getHeaderField('Location').endsWith('/apidocs/index.html')
    }

    private HttpURLConnection get(String path, String ifNoneMatch = null) {
        def conn = (HttpURLConnection) new URL("http://127.0.0.1:${port}${path}").openConnection()
        conn.useCaches = false                 // bypass the JVM's own HTTP cache, so 304s are observable
        conn.instanceFollowRedirects = false   // observe the 302 itself rather than following it
        if (ifNoneMatch != null) {
            conn.setRequestProperty('If-None-Match', ifNoneMatch)
        }
        conn.connect()
        conn
    }
}
