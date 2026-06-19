package org.plukh.spyglass.spring.webmvc.browser

import com.microsoft.playwright.Browser
import com.microsoft.playwright.BrowserContext
import com.microsoft.playwright.Locator
import com.microsoft.playwright.Page
import com.microsoft.playwright.Playwright
import com.microsoft.playwright.Route
import org.plukh.spyglass.spring.webmvc.test.SpyglassTestApp
import groovy.json.JsonSlurper
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.web.context.WebServerApplicationContext
import org.springframework.test.context.ContextConfiguration
import spock.lang.Shared
import spock.lang.Specification

import java.util.function.Consumer

/**
 * Base for the API explorer browser specs. Boots the minimal {@link SpyglassTestApp} on a random
 * port, then drives a headless Chromium against it via Playwright. The browser is created once per
 * spec class; a fresh context/page is used per feature method.
 *
 * <p>Specs assert the explorer's own behaviour (schema-driven form generation, omit-vs-empty
 * serialization, request building and response rendering). The outbound try-it-out request the
 * explorer would make is intercepted with {@code page.route} so no real backend is involved.
 */
@ContextConfiguration
@SpringBootTest(
        classes = [SpyglassTestApp],
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = [
                'springdoc.api-docs.enabled=false',
                'spring.main.web-application-type=servlet'
        ]
)
abstract class SpyglassSpecBase extends Specification {

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

    /** Loads the explorer, optionally deep-linked to an operation (e.g. "POST-/widgets"). */
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

    /** Parses a captured request body (or the empty object) into a map/list. */
    protected Object body(Map captured) {
        json.parseText((captured.postData ?: '{}') as String)
    }

    /** The value input of the (first) Authorization header row. */
    protected Locator authValueInput() {
        page.locator('.he-row').first().locator('.he-val')
    }

    /** The rendered response body text (read-only CodeMirror viewer). */
    protected Locator respBody() {
        page.locator('.response .code-viewer .cm-content')
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
        field(label).locator(':scope > .field-row > .control > input.include, :scope > .field-label > input.include')
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

    /** Fills the three required CreateWidget fields so POST /widgets builds a representative body. */
    protected void fillRequiredWidget(String name = 'W', String priority = 'HIGH', String count = '3') {
        textInput('name').fill(name)
        selectInput('priority').selectOption(priority)
        numberInput('count').fill(count)
    }

    /** A request parameter row (path/query/header) addressed by its exact name. */
    protected Locator param(String name) {
        page.locator("xpath=//*[contains(concat(' ',normalize-space(@class),' '),' param-group ')]" +
                "//label[contains(concat(' ',normalize-space(@class),' '),' field-row ')]" +
                "[.//span[contains(concat(' ',normalize-space(@class),' '),' field-label ') and normalize-space(text())='${name}']]")
    }
}
