package org.plukh.spyglass.spring.webmvc.browser

import com.microsoft.playwright.Route
import com.microsoft.playwright.options.FilePayload

import java.util.function.Consumer

import static com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat

/**
 * Request Log user-facing panel: below the current operation, its own past executions (newest-first,
 * timestamp · status · size), each expandable to the stored request/response, with replay, per-entry
 * delete and a "Clear log". The persistence layer it reads is covered by {@link RequestLogAE}; here we
 * drive the real panel against the real IndexedDB the headless browser provides (no mock).
 *
 * The capture write is fire-and-forget off the render path, so the panel refreshes a tick after a Send
 * settles; assertions wait on the store (recordsForOp) and/or the rendered rows rather than reading
 * straight after the click.
 */
class RequestLogUiAE extends SpyglassSpecBase {

    static final String WIDGET_OP = 'GET /widgets/{id}'

    def "lists the operation's own executions newest-first, with status colour and size"() {
        given:
        open('GET-/widgets/{id}')
        routeWidgets()

        when: 'two executions are run for this operation'
        sendId('7', 1)
        sendId('8', 2)
        waitRendered(2)

        then: 'the panel shows both rows, the newest first, each with a colour-classed status and a size'
        def rows = page.locator('.rl-entry')
        rows.count() == 2
        def newest = rows.first()
        newest.locator('.rl-status').getAttribute('class').contains('ok')
        assertThat(newest.locator('.rl-status')).containsText('200')
        !newest.locator('.rl-size').innerText().trim().isEmpty()

        and: 'the newest row expands to the most recent request (id 8)'
        newest.locator('.rl-row').click()
        assertThat(newest.locator('.rl-target').first()).containsText('/widgets/8')
    }

    def "scopes the log to the operation — switching shows none of another op's executions"() {
        given:
        open('GET-/widgets/{id}')
        routeWidgets()
        sendId('7', 1)
        waitRendered(1)

        when: 'switching in-app (no reload) to a different operation that has never been executed'
        page.evaluate("() => { location.hash = 'POST-/widgets' }")
        page.waitForFunction("() => document.querySelector('.op-header .op-path')?.textContent === '/widgets'")

        then: 'no stale rows leak across the switch — the panel is absent for the un-executed operation'
        page.locator('.rl-entry').count() == 0
        page.locator('.request-log').count() == 0
    }

    def "expands a row to the full request and response, including headers and body"() {
        given:
        open('GET-/widgets/{id}')
        routeWidgets()
        sendId('7', 1)
        waitRendered(1)

        when:
        def entry = page.locator('.rl-entry').first()
        entry.locator('.rl-row').click()

        then: 'both the request and response blocks render'
        assertThat(entry.locator('.rl-block-head').first()).hasText('Request')
        assertThat(entry.locator('.rl-target').first()).containsText('/widgets/7')

        and: 'the response status, at least one header row, and the body are shown'
        assertThat(entry.locator('.resp-status')).containsText('200')
        entry.locator('.resp-headers-row').count() >= 1
        assertThat(entry.locator('.code-viewer .cm-content').last()).containsText('"ok"')
    }

    def "fills in the canonical reason phrase when the stored response carries none"() {
        given: 'three stored responses: an empty phrase, a server-supplied phrase, and an unknown code'
        open('GET-/widgets/{id}')
        page.evaluate('''async () => {
            const m = await import('/apidocs/js/requestLog.js')
            const base = {
                opId: 'GET /widgets/{id}',
                req: { method: 'GET', absUrl: 'http://x/widgets/7', headers: { Accept: 'application/json' } },
                snapshot: { params: {}, mediaType: '', useRaw: false, rawText: '' }
            }
            const resp = (status, statusText) => ({
                status, statusText, durationMs: 1, contentType: 'application/json',
                blob: new Blob(['{"ok":true}']), rawBody: '{"ok":true}',
                headersList: [{ name: 'content-type', value: 'application/json' }]
            })
            await m.recordExecution({ ...base, response: resp(409, '') })
            await m.recordExecution({ ...base, response: resp(200, 'Custom OK') })
            await m.recordExecution({ ...base, response: resp(599, '') })
        }''')

        and: 'a normal execution refreshes the panel'
        routeWidgets()
        param('id').locator('.control input').fill('123')
        page.click('.btn-send')
        waitRendered(4)

        expect: 'the empty phrase falls back to canonical, a supplied phrase is kept verbatim, an unknown code stays bare'
        def labels = page.locator('.rl-status').allInnerTexts()*.trim()
        labels.contains('409 Conflict')
        labels.contains('200 Custom OK')
        labels.contains('599')
        !labels.any { it.startsWith('599 ') }
    }

    def "replays a past entry back into this operation's form"() {
        given:
        open('GET-/widgets/{id}')
        routeWidgets()
        sendId('7', 1)
        waitRendered(1)

        when: 'the field is changed, then a past entry is replayed'
        param('id').locator('.control input').fill('999')
        def entry = page.locator('.rl-entry').first()
        entry.locator('.rl-row').click()
        entry.locator('.rl-actions .btn-mini').first().click()

        then: 'the form is repopulated from the stored request'
        assertThat(param('id').locator('.control input')).hasValue('7')
    }

    def "folds to the first N entries with a working '… +X more'"() {
        given: 'a fold of 2, then three executions'
        page.navigate('/apidocs/index.html?requestLogFoldN=2#GET-/widgets/{id}')
        page.waitForSelector('.op-panel')
        routeWidgets()
        sendId('1', 1)
        sendId('2', 2)
        sendId('3', 3)

        when: 'the panel renders'
        page.waitForSelector('.rl-more')

        then: 'only the first two render, the third is collapsed under the CTA'
        page.locator('.rl-entry').count() == 2
        assertThat(page.locator('.rl-more')).containsText('+1 more')

        when: 'the CTA is clicked'
        page.locator('.rl-more').click()

        then: 'the full retained list is shown and the CTA flips to "Show less"'
        waitRendered(3)
        assertThat(page.locator('.rl-more')).containsText('Show less')

        when: 'the CTA is clicked again'
        page.locator('.rl-more').click()

        then: 'it folds back to the first two'
        waitRendered(2)
        assertThat(page.locator('.rl-more')).containsText('+1 more')
    }

    def "deletes a single entry and clears the whole operation log"() {
        given:
        open('GET-/widgets/{id}')
        routeWidgets()
        sendId('1', 1)
        sendId('2', 2)
        waitRendered(2)

        when: 'the newest entry is deleted'
        def first = page.locator('.rl-entry').first()
        first.locator('.rl-row').click()
        first.locator('.rl-actions .btn-mini.danger').click()

        then: 'one entry remains, in the store and the DOM'
        waitRendered(1)
        records(WIDGET_OP).size() == 1

        when: 'Clear log is pressed'
        page.locator('.btn-clear-log').click()

        then: 'the operation log is emptied and the panel disappears'
        page.waitForFunction("() => document.querySelectorAll('.request-log').length === 0")
        records(WIDGET_OP).isEmpty()
    }

    def "the disable toggle suppresses writes and hides the panel"() {
        given:
        page.navigate('/apidocs/index.html?requestLog=off#GET-/widgets/{id}')
        page.waitForSelector('.op-panel')
        routeWidgets()

        when: 'an operation is executed with the Request Log disabled'
        param('id').locator('.control input').fill('1')
        page.click('.btn-send')
        page.waitForSelector('.response')
        page.waitForTimeout(150)

        then: 'nothing is written and no panel renders'
        records(WIDGET_OP).isEmpty()
        page.locator('.request-log').count() == 0
    }

    def "views a schema-drift entry and replays it best-effort without erroring"() {
        given: 'a stored entry whose snapshot omits the now-required id and carries a vanished query field'
        open('GET-/widgets/{id}')
        page.evaluate('''async () => {
            const m = await import('/apidocs/js/requestLog.js')
            await m.recordExecution({
                opId: 'GET /widgets/{id}',
                req: { method: 'GET', absUrl: 'http://x/widgets/7?ghost=zzz', headers: { Accept: 'application/json' } },
                response: { status: 200, statusText: 'OK', durationMs: 1, contentType: 'application/json',
                    blob: new Blob(['{"ok":true}']), rawBody: '{"ok":true}',
                    headersList: [{ name: 'content-type', value: 'application/json' }] },
                snapshot: { params: { 'query:ghost': 'zzz' }, mediaType: '', useRaw: false, rawText: '' }
            })
        }''')

        and: 'a normal execution that refreshes the panel (and leaves a valid id in the form)'
        routeWidgets()
        param('id').locator('.control input').fill('123')
        page.click('.btn-send')
        waitRendered(2)

        expect: 'a valid form has no warnings, and the drift entry still views correctly'
        page.locator('.warnings').count() == 0
        def drift = page.locator('.rl-entry').last()
        drift.locator('.rl-row').click()
        assertThat(drift.locator('.rl-target').first()).containsText('/widgets/7')

        when: 'the drift snapshot is replayed'
        drift.locator('.rl-actions .btn-mini').first().click()

        then: 'the vanished field is dropped (no error), and the now-required id surfaces as a non-blocking warning'
        assertThat(param('id').locator('.control input')).hasValue('')
        assertThat(page.locator('.warnings')).containsText('id')
        page.locator('.request-log').count() >= 1
    }

    def "expands and collapses an entry from the summary row"() {
        given:
        open('GET-/widgets/{id}')
        routeWidgets()
        sendId('7', 1)
        waitRendered(1)
        def entry = page.locator('.rl-entry').first()

        when:
        entry.locator('.rl-row').click()

        then: 'the detail opens'
        assertThat(entry.locator('.rl-detail')).isVisible()

        when: 'the same row is clicked again'
        entry.locator('.rl-row').click()

        then: 'it collapses'
        entry.locator('.rl-detail').count() == 0
    }

    def "omits the body section entirely when there is no body"() {
        given:
        open('GET-/widgets/{id}')
        routeWidgets()
        sendId('7', 1)
        waitRendered(1)

        when:
        def entry = page.locator('.rl-entry').first()
        entry.locator('.rl-row').click()
        def reqBlock = entry.locator('.rl-block').first()

        then: 'the request block shows no body editor, note, or parts (no "No request body" filler)'
        reqBlock.locator('.code-viewer').count() == 0
        reqBlock.locator('.rl-body-note').count() == 0
        reqBlock.locator('.rl-parts').count() == 0
    }

    def "renders a browser-independent ISO local timestamp"() {
        given:
        open('GET-/widgets/{id}')
        routeWidgets()
        sendId('7', 1)
        waitRendered(1)

        expect:
        page.locator('.rl-entry').first().locator('.rl-time').innerText() ==~ /\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2}/
    }

    def "pretty-prints a JSON body and toggles back to compact"() {
        given:
        open('GET-/widgets/{id}')
        routeWidgets()
        sendId('7', 1)
        waitRendered(1)
        def entry = page.locator('.rl-entry').first()
        entry.locator('.rl-row').click()
        def respBody = entry.locator('.rl-block').last().locator('.code-viewer .cm-content')

        expect: 'pretty-print is on by default — the JSON renders multi-line (space after the colon)'
        assertThat(respBody).containsText('"ok": true')

        when: 'pretty-print is toggled off'
        entry.locator('.rl-block').last().locator('.resp-pretty input').uncheck()

        then: 'the body renders compact'
        assertThat(respBody).containsText('"ok":true')
    }

    def "renders an over-cap or binary body as a plain note, with no editor or copy"() {
        given:
        open('GET-/widgets/{id}')
        page.route('**/widgets/**', ({ Route route ->
            route.fulfill(new Route.FulfillOptions().setStatus(200)
                    .setHeaders(['content-type': 'application/octet-stream'])
                    .setBody('RAWBYTES'))
        } as Consumer<Route>))
        param('id').locator('.control input').fill('1')

        when:
        page.click('.btn-send')
        page.waitForSelector('.response')
        waitForOneRecord(WIDGET_OP)
        waitRendered(1)
        def entry = page.locator('.rl-entry').first()
        entry.locator('.rl-row').click()
        def respBlock = entry.locator('.rl-block').last()

        then: 'the binary body is a note (not a code editor) and has no Copy/Pretty toolbar'
        assertThat(respBlock.locator('.rl-body-note')).containsText('Binary')
        respBlock.locator('.code-viewer').count() == 0
        respBlock.locator('.rl-body-toolbar').count() == 0
    }

    def "renders a multipart request body as a parts list, not an editor"() {
        given:
        open('POST-/uploads')
        field('file').locator('input[type=file]').setInputFiles(
                new FilePayload('hello.txt', 'text/plain', 'hi there'.getBytes('UTF-8')))

        when:
        captureSend('**/uploads')
        waitForOneRecord('POST /uploads')
        page.waitForFunction("() => document.querySelectorAll('.rl-entry').length >= 1")
        def entry = page.locator('.rl-entry').first()
        entry.locator('.rl-row').click()
        def reqBlock = entry.locator('.rl-block').first()

        then: 'the request body is a parts list naming the file, with no code editor'
        assertThat(reqBlock.locator('.rl-parts .rl-part-file')).containsText('hello.txt')
        reqBlock.locator('.code-viewer').count() == 0
    }

    def "a valueless ?requestLog does not re-enable a host-disabled log"() {
        given: 'the host disabled the log via SPYGLASS_CONFIG'
        page.addInitScript('window.SPYGLASS_CONFIG = { requestLog: { enabled: false } }')
        page.navigate('/apidocs/index.html?requestLog#GET-/widgets/{id}')
        page.waitForSelector('.op-panel')
        routeWidgets()

        when: 'an operation is executed; a stray valueless ?requestLog must not override the disable'
        param('id').locator('.control input').fill('1')
        page.click('.btn-send')
        page.waitForSelector('.response')
        page.waitForTimeout(150)

        then: 'still nothing written and no panel'
        records(WIDGET_OP).isEmpty()
        page.locator('.request-log').count() == 0
    }

    // ---- helpers -------------------------------------------------------------

    /** Routes the widget endpoints to a canned 200 JSON so a Send renders a response. */
    private void routeWidgets() {
        page.route('**/widgets/**', ({ Route route -> route.fulfill(jsonFulfill('{"ok":true}')) } as Consumer<Route>))
    }

    /** Fills the id path param, sends, and waits until the store holds at least {@code total} records. */
    private void sendId(String id, int total) {
        param('id').locator('.control input').fill(id)
        page.click('.btn-send')
        page.waitForFunction(
                "async (n) => { const m = await import('/apidocs/js/requestLog.js'); return (await m.recordsForOp('GET /widgets/{id}')).length >= n }",
                total)
    }

    /** Waits until exactly {@code n} log rows are rendered. */
    private void waitRendered(int n) {
        page.waitForFunction("(n) => document.querySelectorAll('.rl-entry').length === n", n)
    }
}
