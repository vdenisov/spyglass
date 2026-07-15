package org.plukh.spyglass.test

import org.springframework.beans.factory.annotation.Value
import spock.lang.Specification

/**
 * Shared base for the management-port isolation specs across every Spring adapter. Boots a host that runs
 * Actuator on a <strong>separate</strong> {@code management.server.port} and asserts, over real HTTP, that
 * the explorer's surfaces &mdash; the friendly redirects ({@code /}, {@code /apidocs}, {@code /apidocs/}),
 * the core assets under {@code /apidocs/**}, and extension assets under {@code /spyglass-ext/**} &mdash; do
 * <strong>not</strong> leak onto the management port, while the primary port serves them exactly as before.
 *
 * <p>The leak arises because Boot's separate-port management context is a child of the primary context and
 * its web infrastructure collects beans (the host's {@code WebMvcConfigurer}s / {@code HandlerMapping}s,
 * including Boot's own default static handler) <em>including ancestors</em> &mdash; so without a guard the
 * explorer shell loads on an admin port where its spec ({@code /v3/api-docs}) isn't served. Actuator
 * endpoints are unaffected either way.
 *
 * <p>Like {@link ExplorerAssetCachingSpecBase} this base is <strong>stack-neutral</strong> and
 * <strong>Boot-version-neutral</strong>: it carries no {@code @SpringBootTest} and references no test
 * application, so each concrete subclass supplies its own boot context (servlet or reactive) with a
 * management port configured. It reads the two random ports from {@code local.server.port} and
 * {@code local.management.port} &mdash; both set by Boot on every version &mdash; and asserts paths as
 * black-box literals (what a browser would request), not against production constants, so the wired-up HTTP
 * behaviour is verified end to end.
 */
abstract class ManagementPortIsolationSpecBase extends Specification {

    @Value('${local.server.port}')
    int port

    @Value('${local.management.port}')
    int managementPort

    def "the explorer surfaces do not leak onto the management port"() {
        expect: 'none of the explorer paths resolve on the admin port'
        code(managementPort, '/') == 404
        code(managementPort, '/apidocs') == 404
        code(managementPort, '/apidocs/') == 404
        code(managementPort, '/apidocs/index.html') == 404
        code(managementPort, '/apidocs/js/app.js') == 404
        code(managementPort, '/spyglass-ext/cache-probe/index.js') == 404
        code(managementPort, '/v3/api-docs') == 404

        and: 'but Actuator itself is served there'
        code(managementPort, '/actuator/health') == 200
    }

    def "the primary port still serves the explorer"() {
        expect: 'the friendly paths redirect to the static entry point'
        redirectsToEntry(port, '/')
        redirectsToEntry(port, '/apidocs')
        redirectsToEntry(port, '/apidocs/')

        and: 'and the assets and spec are served'
        code(port, '/apidocs/index.html') == 200
        code(port, '/apidocs/js/app.js') == 200
        code(port, '/spyglass-ext/cache-probe/index.js') == 200
        code(port, '/v3/api-docs') == 200
    }

    // Helpers are protected, not private: a private superclass method used as a top-level Spock condition
    // (e.g. `redirectsToEntry(...)`) isn't found via dynamic dispatch on the concrete subclass instance.
    protected boolean redirectsToEntry(int p, String path) {
        def conn = get(p, path)
        try {
            conn.responseCode == 302 && conn.getHeaderField('Location').endsWith('/apidocs/index.html')
        } finally {
            conn.disconnect()
        }
    }

    protected int code(int p, String path) {
        def conn = get(p, path)
        try {
            conn.responseCode
        } finally {
            conn.disconnect()
        }
    }

    protected HttpURLConnection get(int p, String path) {
        def conn = (HttpURLConnection) new URL("http://127.0.0.1:${p}${path}").openConnection()
        conn.useCaches = false                 // bypass the JVM's own HTTP cache
        conn.instanceFollowRedirects = false   // observe the 302 itself rather than following it
        conn.connect()
        conn
    }
}
