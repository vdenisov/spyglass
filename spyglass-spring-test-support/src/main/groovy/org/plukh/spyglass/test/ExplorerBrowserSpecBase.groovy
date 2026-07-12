package org.plukh.spyglass.test

import com.microsoft.playwright.Browser
import com.microsoft.playwright.BrowserContext
import com.microsoft.playwright.Locator
import com.microsoft.playwright.Page
import com.microsoft.playwright.Playwright
import com.microsoft.playwright.Route
import groovy.json.JsonSlurper
import org.springframework.beans.factory.annotation.Value
import spock.lang.Shared
import spock.lang.Specification

import java.util.concurrent.atomic.AtomicReference
import java.util.function.Consumer

/**
 * Shared base for the explorer browser specs across every Spring adapter and flavor. Drives a headless
 * Chromium against the explorer via Playwright; the browser is created once per spec class, with a fresh
 * context/page per feature method.
 *
 * <p>This base is <strong>stack-neutral</strong> and <strong>Boot-version-neutral</strong>: it carries no
 * {@code @SpringBootTest} and references no test application, so each concrete subclass supplies its own
 * boot context (servlet or reactive) and the OpenAPI fixture to drive. The subclass only needs to be
 * annotated {@code @SpringBootTest(classes = [SomeTestApp], webEnvironment = RANDOM_PORT, …)}; this base
 * reads the random port from the {@code local.server.port} property (set by the test framework on every
 * Spring Boot version), rather than a Boot-version-specific {@code WebServerApplicationContext} type.
 *
 * <p>The helpers below assert the explorer's own behaviour (navigation, schema-driven form generation,
 * request building, response rendering). The outbound try-it-out request the explorer would make is
 * intercepted with {@code page.route} so no real backend is involved.
 */
abstract class ExplorerBrowserSpecBase extends Specification {

    @Value('${local.server.port}')
    int port

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
                .setBaseURL("http://localhost:${port}")
                .setAcceptDownloads(true)
                .setPermissions(['clipboard-read', 'clipboard-write']))
        page = context.newPage()
    }

    def cleanup() {
        // The update-check timer keeps polling /v3/api-docs in the background; stop that before tearing the
        // context down (drop the route mocks, then unload the document) so a poll still in flight can't
        // fulfill against a closing target and surface a spurious TargetClosedError at close().
        try {
            page?.unrouteAll()
            page?.navigate('about:blank')
        } catch (ignored) {
        }
        context?.close()
    }

    // ---- helpers -------------------------------------------------------------

    /** Loads the explorer, optionally deep-linked to an operation (e.g. "POST-/widgets"). */
    protected Page open(String opHash = '') {
        page.navigate('/apidocs/index.html' + (opHash ? '#' + opHash : ''))
        page.waitForSelector('.sidebar .op-link')
        if (opHash) {
            page.waitForSelector('.op-panel')
        }
        page
    }

    // ---- update-check helpers (shared by the webmvc + webflux specs) ----------

    /** Sub-second poll interval + confirmation window so update-check specs don't wait real minutes. */
    protected static final String UPDATE_CHECK_FAST = 'updateCheckInterval=0.15&updateCheckWindow=0.3'

    /** Opens the explorer with the update check enabled at the given query timings (default: fast). */
    protected Page openWithUpdateCheck(String query = UPDATE_CHECK_FAST) {
        page.navigate('/apidocs/index.html?' + query)
        page.waitForSelector('.sidebar .op-link')
        page
    }

    /** A 200 application/json fulfillment for a mocked spec response. */
    protected static Route.FulfillOptions jsonFulfill(String body) {
        new Route.FulfillOptions().setStatus(200).setContentType('application/json').setBody(body)
    }

    /**
     * Routes {@code /v3/api-docs} to always serve the returned reference's current body; mutate the
     * reference to simulate a spec change on the next poll.
     */
    protected AtomicReference<String> mockSpec(String initialBody) {
        def body = new AtomicReference<String>(initialBody)
        page.route('**/v3/api-docs', ({ Route route -> route.fulfill(jsonFulfill(body.get())) } as Consumer<Route>))
        body
    }

    /** Asserts the update toast does not appear within the given window (a negative, timing-based check). */
    protected void noToastWithin(int ms) {
        page.waitForTimeout(ms)
        assert page.locator('.update-toast').count() == 0
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

    /** Parses a captured request body (or the empty object) into a map/list. */
    protected Object body(Map captured) {
        json.parseText((captured.postData ?: '{}') as String)
    }

    /** The value input of the (first) Authorization header row. */
    protected Locator authValueInput() {
        page.locator('.he-row').first().locator('.he-val')
    }

    /**
     * Fills the Authorization header row with {@code value}, adding the row first when the explorer has
     * none. The explorer ships no default Authorization row (a present-but-empty one is malformed and,
     * on this same-origin explorer, can suppress the cookie/session auth the user already has); a row
     * appears only once a user or extension adds it. The added row is the first, matching where the
     * extension seam's setAuthorization unshifts it.
     */
    protected void fillAuth(String value) {
        if (page.locator('.he-row').count() == 0) {
            page.locator('.he-actions .btn-mini.add').click()
            page.locator('.he-row').first().locator('.he-key').fill('Authorization')
        }
        authValueInput().fill(value)
    }

    /** The rendered response body text (read-only CodeMirror viewer). */
    protected Locator respBody() {
        page.locator('.response .code-viewer .cm-content')
    }

    // ---- Request Log (IndexedDB persistence) ---------------------------------
    //
    // The capture layer (requestLog.js) writes fire-and-forget off the render path, so reads poll the
    // module via waitForFunction (which absorbs the write latency) rather than reading straight after Send.

    /** Waits until the operation has at least one stored record, then returns its first (oldest) record. */
    protected Object waitForOneRecord(String opId) {
        page.waitForFunction(
                "async (op) => { const m = await import('/apidocs/js/requestLog.js'); return (await m.recordsForOp(op)).length >= 1 }",
                opId)
        records(opId)[0]
    }

    /** All records for an operation, oldest-first, as deserialized maps. */
    protected List records(String opId) {
        page.evaluate("async (op) => { const m = await import('/apidocs/js/requestLog.js'); return await m.recordsForOp(op) }", opId) as List
    }

    /** Whether the raw request-log database contains the given substring anywhere (the no-leak proof). */
    protected boolean rawDbContainsSecret(String secret) {
        page.evaluate(
                "async (s) => { const m = await import('/apidocs/js/requestLog.js'); return JSON.stringify(await m.allRecords()).includes(s) }",
                secret) as boolean
    }

    // ---- raw-JSON editor (CodeMirror) ----------------------------------------

    /** Switches the request body between the Form and Raw JSON tabs. */
    protected void clickBodyTab(String name) {
        page.locator('.body-head .body-tabs button', new Page.LocatorOptions().setHasText(name)).click()
    }

    /** The CodeMirror editor in the raw-body section (lazily mounted on first Raw JSON open). */
    protected Locator rawEditor() {
        page.locator('.raw-body .cm-editor')
    }

    /** The editor's current document text, with line breaks preserved. */
    protected String rawText() {
        page.locator('.raw-body .cm-content').innerText()
    }

    /**
     * Replaces the editor's content. {@code insertText} is delivered as a single input event, so
     * CodeMirror's bracket/quote auto-closing doesn't mangle the pasted JSON the way per-key typing
     * would.
     */
    protected void rawFill(String text) {
        page.locator('.raw-body .cm-content').click()
        page.keyboard().press('Control+A')
        page.keyboard().insertText(text)
    }

    // ---- form-field locators -------------------------------------------------
    //
    // Fields are addressed by their exact label text (the `.field-label`'s own text node, which
    // excludes the required "*" and any nested option text). This avoids false matches such as
    // ":has-text('active')" also hitting the "ACTIVE" enum option of another field.

    /** The nearest enclosing `.field` of the field whose label is exactly {@code label}. */
    protected Locator field(String label) {
        page.locator("xpath=//*[contains(concat(' ',normalize-space(@class),' '),' field-label ')]" +
                "[normalize-space(text())='${label}']" +
                "/ancestor::div[contains(concat(' ',normalize-space(@class),' '),' field ')][1]")
    }

    /** The field's own "include" checkbox (omit-vs-send toggle), for leaf fields and objects alike. */
    protected Locator includeBox(String label) {
        field(label).locator(':scope > .field-row > .field-include > input.include, :scope > .field-label > input.include')
    }

    protected Locator textInput(String label) {
        field(label).locator(':scope > .field-row > .control .combobox-input')
    }

    protected Locator numberInput(String label) {
        field(label).locator(':scope > .field-row > .control .combobox-input[type=number]')
    }

    protected Locator selectInput(String label) {
        field(label).locator(':scope > .field-row > .control > select')
    }

    protected Locator constraint(String label) {
        field(label).locator(':scope > .field-row > .control > .constraint')
    }

    protected Locator arrayText(String label) {
        field(label).locator(':scope > .array-body .array-text')
    }

    protected void arrayAdd(String label) {
        field(label).locator(':scope > .array-body > .btn-mini.add').click()
    }

    protected Locator arrayItems(String label) {
        field(label).locator(':scope > .array-body > .array-item')
    }

    protected void mapAdd(String label) {
        field(label).locator(':scope > .map-body > .btn-mini.add').click()
    }

    protected Locator mapEntries(String label) {
        field(label).locator(':scope > .map-body > .map-entry')
    }

    /** A request parameter row (path/query/header) addressed by its exact name. */
    protected Locator param(String name) {
        page.locator("xpath=//*[contains(concat(' ',normalize-space(@class),' '),' param-group ')]" +
                "//label[contains(concat(' ',normalize-space(@class),' '),' field-row ')]" +
                "[.//span[contains(concat(' ',normalize-space(@class),' '),' field-label ') and normalize-space(text())='${name}']]")
    }

    /**
     * The one-per-line textarea of an array-typed parameter, by its exact name. Array params render as a
     * `.param-array` block (label + `array` tag + textarea), not the `label.field-row` that {@link #param}
     * matches, so they need their own locator.
     */
    protected Locator paramArrayText(String name) {
        page.locator("xpath=//*[contains(concat(' ',normalize-space(@class),' '),' param-array ')]" +
                "[.//*[contains(concat(' ',normalize-space(@class),' '),' field-label ') and normalize-space(text())='${name}']]" +
                "//textarea")
    }
}
