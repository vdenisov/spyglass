package org.plukh.spyglass.spring.webflux.browser

import com.microsoft.playwright.Browser
import com.microsoft.playwright.BrowserContext
import com.microsoft.playwright.Locator
import com.microsoft.playwright.Page
import com.microsoft.playwright.Playwright
import com.microsoft.playwright.Route
import org.plukh.spyglass.spring.webflux.test.SpyglassReactiveTestApp
import groovy.json.JsonSlurper
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.web.context.WebServerApplicationContext
import org.springframework.test.context.ContextConfiguration
import spock.lang.Shared
import spock.lang.Specification

import java.util.function.Consumer

/**
 * Base for the reactive per-flavor smoke spec. Boots the minimal {@link SpyglassReactiveTestApp} on a
 * random port (a reactive WebFlux context) and drives a headless Chromium against it via Playwright.
 *
 * <p>This is a trimmed twin of the servlet module's {@code SpyglassSpecBase}: the full front-end
 * behaviour is byte-identical across stacks and already covered there, so this base carries only the
 * handful of helpers the smoke spec needs — enough to prove the explorer loads and executes over the
 * reactive serving stack. {@code WebServerApplicationContext} resolves the random port for the reactive
 * context just as it does for the servlet one.
 */
@ContextConfiguration
@SpringBootTest(
        classes = [SpyglassReactiveTestApp],
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = [
                'springdoc.api-docs.enabled=false',
                'spring.main.web-application-type=reactive'
        ]
)
abstract class SpyglassReactiveSpecBase extends Specification {

    @Autowired
    WebServerApplicationContext server

    @Shared
    Playwright playwright

    @Shared
    Browser browser

    BrowserContext context
    Page page

    final JsonSlurper json = new JsonSlurper()

    def setupSpec() {
        playwright = Playwright.create()
        browser = playwright.chromium().launch()
    }

    def cleanupSpec() {
        browser?.close()
        playwright?.close()
    }

    def setup() {
        context = browser.newContext(new Browser.NewContextOptions()
                .setBaseURL("http://localhost:${server.webServer.port}")
                .setAcceptDownloads(true)
                .setPermissions(['clipboard-read', 'clipboard-write']))
        page = context.newPage()
    }

    def cleanup() {
        context?.close()
    }

    // ---- helpers -------------------------------------------------------------

    /** Loads the explorer, optionally deep-linked to an operation (e.g. "GET-/widgets/{id}"). */
    protected Page open(String opHash = '') {
        page.navigate('/apidocs/index.html' + (opHash ? '#' + opHash : ''))
        page.waitForSelector('.sidebar .op-link')
        if (opHash) {
            page.waitForSelector('.op-panel')
        }
        page
    }

    /**
     * Clicks Send with the outgoing request intercepted, returning the captured
     * {@code [method, url, postData, headers]} and fulfilling a canned 200 so the response renders.
     */
    protected Map captureSend(String urlGlob) {
        Map captured = [:]
        page.route(urlGlob, ({ Route route ->
            def req = route.request()
            captured.method = req.method()
            captured.url = req.url()
            captured.postData = req.postData()
            captured.headers = req.headers()
            route.fulfill(new Route.FulfillOptions()
                    .setStatus(200)
                    .setContentType('application/json')
                    .setBody('{"ok":true}'))
        } as Consumer<Route>))
        page.click('.btn-send')
        page.waitForSelector('.response')
        captured
    }

    /** The rendered response body text (read-only CodeMirror viewer). */
    protected Locator respBody() {
        page.locator('.response .code-viewer .cm-content')
    }

    /** A request parameter row (path/query/header) addressed by its exact name. */
    protected Locator param(String name) {
        page.locator("xpath=//*[contains(concat(' ',normalize-space(@class),' '),' param-group ')]" +
                "//label[contains(concat(' ',normalize-space(@class),' '),' field-row ')]" +
                "[.//span[contains(concat(' ',normalize-space(@class),' '),' field-label ') and normalize-space(text())='${name}']]")
    }
}
