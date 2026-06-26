package org.plukh.spyglass.spring.webmvc.browser

import com.microsoft.playwright.Page
import com.microsoft.playwright.Route
import com.microsoft.playwright.options.FilePayload

import java.util.function.Consumer

import static com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat

/**
 * Try-it-out request building and execution: parameters carry no include toggles, path/query/header
 * inputs are assembled into the outgoing request, the response is rendered (content-type aware, with
 * pretty-print/copy/download), and curl/.http are copied.
 */
class TryItOutAE extends SpyglassSpecBase {

    def setup() {
        open('GET-/widgets/{id}')
    }

    def "parameters have no include toggles"() {
        expect:
        page.locator('.params input.include').count() == 0
    }

    def "assembles the path, query and headers from the inputs"() {
        given:
        param('id').locator('.control input').fill('42')
        param('verbose').locator('.control select').selectOption('true')
        paramArrayText('fields').fill('a\nb')
        param('X-Trace').locator('.control input').fill('tid')

        when:
        def cap = captureSend('**/widgets/**')

        then:
        cap.method == 'GET'
        cap.url.contains('/widgets/42?')
        cap.url.contains('verbose=true')
        cap.url.contains('fields=a')
        cap.url.contains('fields=b')
        cap.url.contains('limit=20')          // the schema default, pre-filled
        cap.headers['x-trace'] == 'tid'       // request header names are lower-cased
    }

    def "sends user-added global headers with the request"() {
        given:
        param('id').locator('.control input').fill('1')
        page.locator('.he-actions .btn-mini.add').click()
        def row = page.locator('.he-row').last()
        row.locator('.he-key').fill('X-Custom')
        row.locator('.he-val').fill('cv')

        when:
        def cap = captureSend('**/widgets/**')

        then:
        cap.headers['x-custom'] == 'cv'
    }

    def "sends Accept: application/json by default"() {
        given:
        param('id').locator('.control input').fill('1')

        when:
        def cap = captureSend('**/widgets/**')

        then:
        cap.headers['accept'] == 'application/json'
    }

    def "lets the Accept header be overridden from the top bar"() {
        given:
        param('id').locator('.control input').fill('1')
        page.locator('.accept-field input').fill('*/*')

        when:
        def cap = captureSend('**/widgets/**')

        then:
        cap.headers['accept'] == '*/*'
    }

    def "preselects an endpoint's declared Accept so a binary endpoint doesn't 406"() {
        when: 'opening an endpoint that only produces image/png'
        open('GET-/image')

        then: 'the Accept field switches from the JSON default to image/png'
        page.locator('.accept-field .combobox-input').inputValue() == 'image/png'

        when:
        def cap = captureSend('**/image')

        then:
        cap.headers['accept'] == 'image/png'
    }

    def "lets an in-flight request be cancelled"() {
        given: 'a route that never responds, so the request stays in flight'
        page.route('**/widgets/**', ({ Route route -> /* held open: never fulfilled */ } as Consumer<Route>))
        param('id').locator('.control input').fill('1')

        when: 'the request is sent, then cancelled while it is in flight'
        page.click('.btn-send')
        page.waitForSelector('.btn-cancel')
        page.click('.btn-cancel')

        then: 'the response reports the cancel (not a network error) and Send is usable again'
        assertThat(page.locator('.response')).containsText('Request cancelled')
        assertThat(page.locator('.btn-send')).isEnabled()
        page.locator('.btn-cancel').count() == 0
    }

    def "double-clicking Send does not abort the request"() {
        given: 'a route that never responds, so the request stays in flight'
        page.route('**/widgets/**', ({ Route route -> /* held open: never fulfilled */ } as Consumer<Route>))
        param('id').locator('.control input').fill('1')

        when: 'Send is double-clicked'
        page.dblclick('.btn-send')

        then: 'Send is disabled while in flight, so click 2 is a no-op — the request runs, nothing is cancelled'
        page.waitForSelector('.btn-cancel')          // a request is in flight (separate Cancel control shown)
        assertThat(page.locator('.btn-send')).isDisabled()
        page.locator('.response').count() == 0       // no "Request cancelled" — click 2 did not reach Cancel
    }

    def "renders the response status, duration and body"() {
        given:
        param('id').locator('.control input').fill('1')

        when:
        captureSend('**/widgets/**')

        then:
        assertThat(page.locator('.resp-status')).containsText('200')
        page.locator('.resp-status').getAttribute('class').contains('ok')
        assertThat(respBody()).containsText('ok')
    }

    def "copies an equivalent curl command to the clipboard"() {
        given:
        param('id').locator('.control input').fill('42')

        when:
        page.locator('button', new Page.LocatorOptions().setHasText('Copy as cURL')).click()

        then:
        assertThat(page.locator('.copied-note')).isVisible()
        def clip = page.evaluate('() => navigator.clipboard.readText()') as String
        clip.contains('curl -X GET')
        clip.contains('/widgets/42')
    }

    def "copies a JetBrains .http request to the clipboard"() {
        given:
        param('id').locator('.control input').fill('42')
        fillAuth('signature tok')

        when:
        page.locator('button', new Page.LocatorOptions().setHasText('Copy as JetBrains .http')).click()

        then:
        assertThat(page.locator('.copied-note')).isVisible()
        def clip = page.evaluate('() => navigator.clipboard.readText()') as String
        clip.startsWith('GET http')                  // "METHOD url", not a curl command
        clip.contains('/widgets/42')
        clip.contains('Authorization: signature tok') // the user-added global header row is emitted
        !clip.contains('curl')
    }

    def "formats the .http body as a blank-line-separated, pretty-printed JSON block"() {
        given:
        open('POST-/widgets')
        fillRequiredWidget()

        when:
        page.locator('button', new Page.LocatorOptions().setHasText('Copy as JetBrains .http')).click()

        then:
        // The clipboard normalizes newlines to CRLF on Windows; compare against LF.
        def clip = (page.evaluate('() => navigator.clipboard.readText()') as String).replace('\r\n', '\n')
        clip.startsWith('POST http')
        clip.contains('Content-Type: application/json')
        clip.contains('\n\n{')                        // blank line between headers and body
        clip.contains('\n  "name": "W"')              // body is pretty-printed (2-space indent)
    }

    def "documents response schemas in the Schema tab"() {
        when:
        page.locator('.op-tabs button', new Page.LocatorOptions().setHasText('Schema')).click()

        then:
        page.locator('.schema-doc .resp-code').allTextContents().containsAll(['200', '404'])
        page.locator('.schema-doc .stree-name').allTextContents().containsAll(['id', 'name', 'count', 'tags'])
    }

    // ---- execute shortcut (Ctrl/Cmd+Enter) -----------------------------------

    def "Ctrl+Enter sends the request"() {
        given:
        param('id').locator('.control input').fill('7')
        Map captured = [:]
        page.route('**/widgets/**', ({ Route route ->
            captured.method = route.request().method()
            captured.url = route.request().url()
            route.fulfill(new Route.FulfillOptions().setStatus(200).setContentType('application/json').setBody('{"ok":true}'))
        } as Consumer<Route>))

        when:
        page.keyboard().press('Control+Enter')
        page.waitForSelector('.response')

        then:
        captured.method == 'GET'
        captured.url.contains('/widgets/7')
    }

    def "Ctrl+Enter sends from the Raw JSON editor instead of inserting a newline"() {
        given:
        open('POST-/widgets')
        fillRequiredWidget()
        clickBodyTab('Raw JSON')
        page.locator('.raw-body .cm-content').click()
        Map captured = [:]
        page.route('**/widgets**', ({ Route route ->
            captured.method = route.request().method()
            route.fulfill(new Route.FulfillOptions().setStatus(200).setContentType('application/json').setBody('{"ok":true}'))
        } as Consumer<Route>))

        when:
        page.keyboard().press('Control+Enter')
        page.waitForSelector('.response')

        then:
        captured.method == 'POST'
    }

    def "shows the send shortcut hint"() {
        expect:
        assertThat(page.locator('.send-bar .kbd-hint')).isVisible()
        !page.locator('.send-bar .kbd-hint').textContent().isEmpty()
    }

    // ---- non-JSON request bodies ---------------------------------------------

    def "sends a urlencoded body with the urlencoded Content-Type"() {
        given:
        open('POST-/form')
        textInput('name').fill('Ada')
        numberInput('count').fill('3')

        when:
        def cap = captureSend('**/form')

        then:
        cap.headers['content-type'].startsWith('application/x-www-form-urlencoded')
        cap.postData.contains('name=Ada')
        cap.postData.contains('count=3')
    }

    def "sends a multipart body carrying the chosen file"() {
        given:
        open('POST-/uploads')
        field('file').locator('input[type=file]').setInputFiles(
                new FilePayload('hello.txt', 'text/plain', 'hi there'.getBytes('UTF-8')))

        when:
        def cap = captureSend('**/uploads')

        then: 'the browser sets a multipart content type and the body carries the file part'
        cap.headers['content-type'].startsWith('multipart/form-data')
        cap.postData.contains('filename="hello.txt"')
        cap.postData.contains('hi there')
    }

    def "copies a multipart request as cURL with an -F file reference"() {
        given:
        open('POST-/uploads')
        field('file').locator('input[type=file]').setInputFiles(
                new FilePayload('hello.txt', 'text/plain', 'hi'.getBytes('UTF-8')))

        when:
        page.locator('button', new Page.LocatorOptions().setHasText('Copy as cURL')).click()

        then:
        def clip = page.evaluate('() => navigator.clipboard.readText()') as String
        clip.contains("-F 'file=@/path/to/hello.txt'")
    }

    // ---- response rendering (content-type aware) -----------------------------

    def "renders a JSON response in the read-only viewer, pretty-printed by default"() {
        given:
        stub('application/json', '{"a":1,"b":[2,3]}')

        when:
        sendForResponse()

        then:
        page.locator('.resp-pretty input').isChecked()
        assertThat(respBody()).containsText('"a": 1')          // 2-space pretty-print
    }

    def "toggling Pretty off shows the raw body and persists the preference"() {
        given:
        stub('application/json', '{"a":1}')
        sendForResponse()

        when:
        page.locator('.resp-pretty input').uncheck()

        then:
        assertThat(respBody()).containsText('{"a":1}')          // compact, as received
        page.evaluate("() => localStorage.getItem('apidocs-response-pretty')") == 'false'
    }

    def "renders a text/HTML response as text with no Pretty checkbox"() {
        given:
        stub('text/html', '<p>hi</p>')

        when:
        sendForResponse()

        then:
        page.locator('.resp-pretty').count() == 0
        assertThat(respBody()).containsText('<p>hi</p>')
    }

    def "offers download only for a binary response"() {
        given:
        stub('application/octet-stream', 'RAWBYTES')

        when:
        sendForResponse()

        then:
        page.locator('.code-viewer').count() == 0
        assertThat(page.locator('.response .resp-binary')).containsText('Binary response')
        assertThat(page.locator('.resp-toolbar button', new Page.LocatorOptions().setHasText('Download'))).isVisible()
    }

    def "copies the response body to the clipboard"() {
        given:
        stub('application/json', '{"a":1}')
        sendForResponse()

        when:
        page.locator('.resp-toolbar button', new Page.LocatorOptions().setHasText('Copy')).click()

        then:
        def clip = page.evaluate('() => navigator.clipboard.readText()') as String
        clip.contains('"a": 1')
    }

    def "previews an image response as a thumbnail with no Copy"() {
        given:
        byte[] png = Base64.decoder.decode('iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+M9QDwADhgGAWjR9awAAAABJRU5ErkJggg==')
        page.route('**/widgets/**', ({ Route route ->
            route.fulfill(new Route.FulfillOptions().setStatus(200).setContentType('image/png').setBodyBytes(png))
        } as Consumer<Route>))

        when:
        sendForResponse()

        then: 'an inline image, a Download button, and no Copy (bytes-as-text is meaningless)'
        assertThat(page.locator('.resp-image img')).isVisible()
        assertThat(page.locator('.resp-toolbar button', new Page.LocatorOptions().setHasText('Download'))).isVisible()
        page.locator('.resp-toolbar button', new Page.LocatorOptions().setHasText('Copy')).count() == 0
    }

    def "names the download from Content-Disposition when present"() {
        given:
        page.route('**/widgets/**', ({ Route route ->
            route.fulfill(new Route.FulfillOptions().setStatus(200)
                    .setHeaders(['content-type': 'application/octet-stream', 'content-disposition': 'attachment; filename="server-name.bin"'])
                    .setBody('RAWBYTES'))
        } as Consumer<Route>))
        sendForResponse()

        when:
        def download = page.waitForDownload({ ->
            page.locator('.resp-toolbar button', new Page.LocatorOptions().setHasText('Download')).click()
        })

        then:
        download.suggestedFilename() == 'server-name.bin'
    }

    def "falls back to the operation-derived name + extension without Content-Disposition"() {
        given:
        stub('application/octet-stream', 'RAWBYTES')
        sendForResponse()

        when:
        def download = page.waitForDownload({ ->
            page.locator('.resp-toolbar button', new Page.LocatorOptions().setHasText('Download')).click()
        })

        then:
        download.suggestedFilename().endsWith('.bin')
        download.suggestedFilename().contains('widgets')
    }

    def "renders an over-limit JSON response unformatted, without the pretty-print toggle"() {
        given: 'a JSON response just over the ~2 MB pretty-print size limit'
        def line = '    "' + ('x' * 72) + '",\n'   // ~80 chars per line
        def big = '{\n  "items": [\n' + (line * 26000) + '    "end"\n  ]\n}'
        stub('application/json', big)

        when:
        sendForResponse()

        then: 'it falls back to plain text — the large-response note shows and the pretty toggle is gone'
        assertThat(page.locator('.resp-toolarge')).isVisible()
        page.locator('.resp-pretty').count() == 0

        and: 'the body still renders in the viewer'
        assertThat(page.locator('.response .code-viewer')).isVisible()
    }

    // ---- helpers -------------------------------------------------------------

    /** Intercepts the GET /widgets/{id} request and fulfills it with the given content type and body. */
    private void stub(String contentType, String body, int status = 200) {
        page.route('**/widgets/**', ({ Route route ->
            route.fulfill(new Route.FulfillOptions().setStatus(status).setContentType(contentType).setBody(body))
        } as Consumer<Route>))
    }

    /** Fills the path id (so the URL is valid) and sends, waiting for the rendered response. */
    private void sendForResponse() {
        param('id').locator('.control input').fill('1')
        page.click('.btn-send')
        page.waitForSelector('.response')
    }
}
