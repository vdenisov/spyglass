package org.plukh.spyglass.spring.webmvc.browser

import com.microsoft.playwright.Locator

import static com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat

/**
 * Shareable request deep-links (#24): "Copy link" encodes the filled-in operation form plus the
 * non-secret global header rows into the URL fragment; opening such a link rehydrates the form through
 * the same best-effort replay path the Request Log uses. The fragment never reaches the server.
 *
 * <p>The security contract is the point of the feature: auth fields never travel in a shared link. The
 * Authorization header row and any field mapped to an apiKey securityScheme (here the {@code api_key}
 * query parameter of {@code GET /secure/{id}}) are dropped before encoding; benign routing/sandbox
 * headers are carried so the link actually reproduces the request. On load the link's header set
 * replaces the recipient's, but the recipient's own Authorization row is preserved (the link never
 * carried it), so a shared link can never blank a colleague's token.
 */
class DeepLinkAE extends SpyglassSpecBase {

    def "Copy link carries benign fields but excludes the Authorization header and apiKey query param"() {
        given:
        open('GET-/secure/{id}')
        param('id').locator('.control input').fill('42')
        queryInput('api_key').fill('SECRET-KEY')
        queryInput('region').fill('eu-west')
        fillAuth('Bearer topsecret')
        addHeaderRow('X-Sandbox', 'sandbox-7')

        when: 'the link is copied and its fragment decoded back to the payload'
        def payload = decodePayload(copyShareLink())

        then: 'the benign query param and the routing header travel'
        payload.snap.params['query:region'] == 'eu-west'
        payload.snap.params['path:id'] == '42'
        payload.headers.any { it.key == 'X-Sandbox' && it.value == 'sandbox-7' }

        and: 'the apiKey query param and the Authorization header are dropped before encoding'
        !payload.snap.params.containsKey('query:api_key')
        !payload.headers.any { (it.key as String).equalsIgnoreCase('authorization') }
    }

    def "carries a request body and its media type, and rehydrates them on open"() {
        given: 'a POST with a filled JSON body'
        open('POST-/widgets')
        fillRequiredWidget('blue-team', 'LOW', '7')

        when: 'the link is copied and decoded'
        def url = copyShareLink()
        def payload = decodePayload(url)

        then: 'the body and its media type travel'
        payload.snap.mediaType == 'application/json'
        payload.snap.body.name == 'blue-team'
        payload.snap.body.priority == 'LOW'
        (payload.snap.body.count as int) == 7

        when: 'a fresh recipient opens the link'
        page.evaluate('() => { localStorage.clear(); sessionStorage.clear(); }')
        page.navigate(url)
        page.waitForSelector('.op-panel')

        then: 'the body form rehydrates with the same values'
        assertThat(page.locator('.op-header .op-path')).hasText('/widgets')
        assertThat(textInput('name')).hasValue('blue-team')
        assertThat(selectInput('priority')).hasValue('LOW')
        assertThat(numberInput('count')).hasValue('7')
    }

    def "opening a shared link rehydrates the operation form and applies the link's headers"() {
        given:
        open('GET-/secure/{id}')
        param('id').locator('.control input').fill('42')
        queryInput('region').fill('eu-west')
        addHeaderRow('X-Sandbox', 'sandbox-7')

        when: 'the link is copied, storage is wiped to simulate a fresh recipient, and the link is opened'
        def url = copyShareLink()
        page.evaluate('() => { localStorage.clear(); sessionStorage.clear(); }')
        page.navigate(url)
        page.waitForSelector('.op-panel')

        then: 'the operation and its non-secret params rehydrate'
        assertThat(page.locator('.op-header .op-path')).hasText('/secure/{id}')
        assertThat(param('id').locator('.control input')).hasValue('42')
        assertThat(queryInput('region')).hasValue('eu-west')

        and: "the link's non-secret header is applied"
        waitForHeader('X-Sandbox', 'sandbox-7')
    }

    def "opening a shared link replaces the header set but preserves the recipient's own Authorization"() {
        given: 'a sender copies a link carrying a benign header'
        open('GET-/secure/{id}')
        addHeaderRow('X-Sandbox', 'sandbox-7')
        def url = copyShareLink()

        and: 'a fresh recipient already has their own Authorization token and an unrelated header'
        page.evaluate('() => { localStorage.clear(); sessionStorage.clear(); }')
        open()
        fillAuth('Bearer MY-OWN-TOKEN')
        addHeaderRow('X-Old', 'stale')

        when: 'the recipient opens the shared link'
        page.navigate(url)
        page.waitForSelector('.op-panel')

        then: "the link's header is applied and the recipient's own Authorization is kept"
        waitForHeader('X-Sandbox', 'sandbox-7')
        headerRowValue('Authorization') == 'Bearer MY-OWN-TOKEN'

        and: "the recipient's unrelated header is replaced away (the link's set wins)"
        headerRowValue('X-Old') == null
    }

    def "Copy link refuses over the size cap, leaving the clipboard untouched"() {
        given: 'a tiny cap so any real request exceeds it, and a sentinel already on the clipboard'
        page.navigate('/apidocs/index.html?shareLinkMaxUrl=80#GET-/secure/{id}')
        page.waitForSelector('.op-panel')
        param('id').locator('.control input').fill('42')
        page.evaluate("() => navigator.clipboard.writeText('SENTINEL')")

        when:
        page.click('.btn-share-link')

        then: 'a refusal message shows, no "Copied" cue appears, and the clipboard is unchanged'
        assertThat(page.locator('.share-error')).isVisible()
        page.locator('.share-error').textContent().contains('too large')
        page.locator('.copied-note').count() == 0
        page.evaluate("() => navigator.clipboard.readText()") == 'SENTINEL'
    }

    def "ignores a malformed deep-link blob, still opening the operation from its anchor"() {
        when: 'a link whose state segment is not a valid blob is opened'
        page.navigate('/apidocs/index.html#GET-/secure/{id}&s=@@not-valid@@')
        page.waitForSelector('.op-panel')

        then: 'the operation opens from its anchor; the bad state is ignored (no crash, form at defaults)'
        assertThat(page.locator('.op-header .op-path')).hasText('/secure/{id}')
        param('id').locator('.control input').inputValue() == ''
    }

    def "the codec round-trips a payload, and rejects a bad blob or an unknown version"() {
        given:
        open('GET-/secure/{id}')

        expect: 'encode/decode is loss-free behind a <codec><version> prefix; garbage and a future version decode to null'
        page.evaluate('''async () => {
            const m = await import('/apidocs/js/shareLink.js')
            const obj = { p: { 'q:region': 'eu-west' }, h: [['X-Sandbox', 'sandbox-7']] }
            const enc = await m.encodeState(obj)
            const dec = await m.decodeState(enc)
            return {
                roundTrips: JSON.stringify(dec) === JSON.stringify(obj),
                codecOk: enc[0] === 'D' || enc[0] === 'R',
                version: enc[1],
                wrongVersion: await m.decodeState(enc[0] + '9' + enc.slice(2)),
                bad: await m.decodeState('not-a-valid-blob')
            }
        }''') == [roundTrips: true, codecOk: true, version: '1', wrongVersion: null, bad: null]
    }

    // ---- helpers -------------------------------------------------------------

    /** The value input of the request parameter (path/query/header) with the given name. */
    private Locator queryInput(String name) {
        param(name).locator('.control input')
    }

    /** Adds a global header row and fills its name/value (appended as the last row). */
    private void addHeaderRow(String key, String value) {
        page.locator('.he-actions .btn-mini.add').click()
        def row = page.locator('.he-row').last()
        row.locator('.he-key').fill(key)
        row.locator('.he-val').fill(value)
    }

    /** The value of the header row whose name is {@code key}, or null when no such row exists. */
    private String headerRowValue(String key) {
        page.evaluate('''(k) => {
            const row = [...document.querySelectorAll('.he-row')].find(r => r.querySelector('.he-key')?.value === k)
            return row ? row.querySelector('.he-val').value : null
        }''', key) as String
    }

    /** Waits until a header row with the given name carries the given value (deep-link apply is async). */
    private void waitForHeader(String key, String value) {
        page.waitForFunction('''([k, v]) => {
            const row = [...document.querySelectorAll('.he-row')].find(r => r.querySelector('.he-key')?.value === k)
            return !!row && row.querySelector('.he-val').value === v
        }''', [key, value] as Object[])
    }

    /** Clicks "Copy link", waits for the success cue, and returns the copied URL from the clipboard. */
    private String copyShareLink() {
        page.click('.btn-share-link')
        page.waitForSelector('.copied-note')
        page.evaluate('() => navigator.clipboard.readText()') as String
    }

    /** Decodes and expands the "&s=<blob>" fragment of a shared URL into the { snap, headers } payload. */
    private Object decodePayload(String url) {
        def sep = '&s='
        def frag = url.substring(url.indexOf(sep) + sep.length())
        page.evaluate("async (s) => { const m = await import('/apidocs/js/shareLink.js'); return m.expandSharePayload(await m.decodeState(s)) }", frag)
    }
}
