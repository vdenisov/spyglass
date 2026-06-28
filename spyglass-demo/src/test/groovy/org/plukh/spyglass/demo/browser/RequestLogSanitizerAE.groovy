package org.plukh.spyglass.demo.browser

import com.microsoft.playwright.Route

import java.util.function.Consumer

/**
 * End-to-end check of the bundled sample extension's Request Log sanitizer (the fourth seam hook,
 * {@code api.requestLog.registerSanitizer}), proving the spec-advertised, auto-load path with no
 * {@code ?ext=}. {@code POST /apidocs-demo/secrets} deliberately carries a secret on every surface a
 * value can land on — a query param, a request header, a request-body field, a response header and a
 * response-body field — and the same query secret is stored more than once (the serialized URL, the
 * replay snapshot {@code request.params}, and {@code response.finalUrl}). The sample sanitizer redacts
 * each before the record is persisted; this spec asserts every surface is masked, the non-sensitive
 * {@code note} survives, the core Authorization mask still runs (ordering: core first, extension after),
 * and the raw IndexedDB never contains any secret (the write-time guarantee).
 */
class RequestLogSanitizerAE extends SpyglassDemoSpecBase {

    private static final String SECRETS_OP = 'POST /apidocs-demo/secrets'

    def "the sample sanitizer redacts every secret-bearing surface before the record is persisted"() {
        given: 'the secrets operation open, the sample extension loaded, and a secret in each request surface'
        open('POST-/apidocs-demo/secrets')
        page.waitForSelector('.demo-panel')              // the sample extension (and its sanitizer) has registered
        fillAuth('auth-token-SECRET')
        param('apiKey').locator('.control input').fill('query-key-SECRET')
        param('X-Demo-Api-Key').locator('.control input').fill('header-key-SECRET')
        clickBodyTab('Raw JSON')
        rawFill('{"secret":"body-secret-SECRET","note":"keep-me"}')

        when: 'the response also carries secrets — a session header and a session-token body field'
        page.route('**/apidocs-demo/secrets**', ({ Route route ->
            route.fulfill(new Route.FulfillOptions().setStatus(200)
                    .setHeaders(['content-type': 'application/json', 'x-demo-session': 'session-id-SECRET'])
                    .setBody('{"message":"ok","sessionToken":"session-token-SECRET","note":"keep-me"}'))
        } as Consumer<Route>))
        page.click('.btn-send')
        page.waitForSelector('.response')

        then: 'the URL query secret is masked in both the request URL and the final URL'
        def rec = waitForOneRecord(SECRETS_OP)
        rec.request.url.toString().contains('apiKey=***')
        !rec.request.url.toString().contains('query-key-SECRET')
        rec.response.finalUrl.toString().contains('apiKey=***')
        !rec.response.finalUrl.toString().contains('query-key-SECRET')

        and: 'request + response headers are masked, and the core Authorization mask still ran (core first)'
        rec.request.headers['X-Demo-Api-Key'] == '***'
        rec.request.headers['Authorization'] == '***'
        rec.response.headers['x-demo-session'] == '***'

        and: 'the body-field secrets are masked while the non-sensitive note survives'
        rec.request.body.toString().contains('"secret":"***"')
        rec.request.body.toString().contains('keep-me')
        rec.response.body.toString().contains('"sessionToken":"***"')
        !rec.response.body.toString().contains('session-token-SECRET')

        and: "the replay snapshot's own copies are masked too"
        rec.request.params.params['query:apiKey'] == '***'
        rec.request.params.params['header:X-Demo-Api-Key'] == '***'

        and: 'no secret reached disk on any surface (the write-time guarantee)'
        ['query-key-SECRET', 'header-key-SECRET', 'body-secret-SECRET',
         'auth-token-SECRET', 'session-id-SECRET', 'session-token-SECRET'].each {
            assert !rawDbContainsSecret(it)
        }
    }
}
