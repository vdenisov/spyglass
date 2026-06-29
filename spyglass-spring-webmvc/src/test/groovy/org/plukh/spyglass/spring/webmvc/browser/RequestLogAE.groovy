package org.plukh.spyglass.spring.webmvc.browser

import com.microsoft.playwright.Route
import com.microsoft.playwright.options.FilePayload

import java.util.function.Consumer

/**
 * Request Log persistence layer: each completed execution is captured as an immutable,
 * sanitized, size-bounded record in IndexedDB, scoped per operation, with FIFO eviction. This spec
 * asserts the capture/store modules directly against the real IndexedDB the headless browser provides
 * (no mock); the user-facing panel (list, expand, replay, clear, config) is covered by
 * {@link RequestLogUiAE}. Records bind to their operation by opId ("&lt;METHOD&gt; &lt;path&gt;").
 *
 * Capture is fire-and-forget off the render path, so assertions never read straight after Send: they
 * poll the read helper via waitForFunction (which absorbs the write latency), or call the module
 * directly and await the write for the deterministic unit-level cases (eviction, truncation, masking).
 */
class RequestLogAE extends SpyglassSpecBase {

    def "captures a completed execution, queryable by opId"() {
        given:
        open('GET-/widgets/{id}')
        param('id').locator('.control input').fill('7')

        when:
        captureSend('**/widgets/**')

        then: 'one record is stored for this operation, snapshotting the request and the response'
        def rec = waitForOneRecord('GET /widgets/{id}')
        rec.opId == 'GET /widgets/{id}'
        rec.request.method == 'GET'
        rec.request.url.toString().contains('/widgets/7')
        (rec.response.status as int) == 200
        rec.response.body == '{"ok":true}'
        (rec.response.size as int) == '{"ok":true}'.bytes.length
    }

    def "stores an oversized body as a byte-count placeholder, keeping the true size"() {
        given:
        open('GET-/widgets/{id}')

        when:
        def r = page.evaluate('''async () => {
            const m = await import('/apidocs/js/requestLog.js')
            const big = 'x'.repeat(40000)
            await m.recordExecution({
                opId: 'GET /big',
                req: { method: 'POST', absUrl: 'http://x/big', headers: { 'Content-Type': 'text/plain' }, bodyStr: big },
                response: { status: 200, statusText: 'OK', durationMs: 1, contentType: 'text/plain', blob: new Blob([big]), rawBody: big, headersList: [] },
                snapshot: {}
            })
            const recs = await m.recordsForOp('GET /big')
            return { count: recs.length, reqBody: recs[0].request.body, reqInfo: recs[0].request.bodyInfo,
                     respBody: recs[0].response.body, respInfo: recs[0].response.bodyInfo, size: recs[0].response.size }
        }''')

        then: 'neither body is stored; each carries a truncated descriptor with the true byte count'
        (r.count as int) == 1
        r.reqBody == null
        r.reqInfo.kind == 'truncated'
        (r.reqInfo.bytes as int) == 40000
        r.reqInfo.contentType == 'text/plain'
        r.respBody == null
        r.respInfo.kind == 'truncated'
        (r.respInfo.bytes as int) == 40000
        (r.size as int) == 40000
    }

    def "keeps an upload's file name and size, never its bytes"() {
        given:
        open('POST-/uploads')
        field('file').locator('input[type=file]').setInputFiles(
                new FilePayload('hello.txt', 'text/plain', 'hi there'.getBytes('UTF-8')))

        when:
        captureSend('**/uploads')

        then: 'the multipart parts name the file and its size but never store its contents'
        def rec = waitForOneRecord('POST /uploads')
        rec.request.body == null
        rec.request.bodyInfo.kind == 'multipart'
        rec.request.bodyInfo.parts.any { it.filename == 'hello.txt' }
        !rec.request.bodyInfo.parts.any { it.value?.contains('hi there') }
    }

    def "placeholds a binary response, keeping the Content-Disposition filename"() {
        given:
        open('GET-/widgets/{id}')
        page.route('**/widgets/**', ({ Route route ->
            route.fulfill(new Route.FulfillOptions().setStatus(200)
                    .setHeaders(['content-type': 'application/octet-stream', 'content-disposition': 'attachment; filename="server-name.bin"'])
                    .setBody('RAWBYTES'))
        } as Consumer<Route>))
        param('id').locator('.control input').fill('1')

        when:
        page.click('.btn-send')
        page.waitForSelector('.response')

        then: 'the body is elided to a binary descriptor (name + type) and size is the true byte count'
        def rec = waitForOneRecord('GET /widgets/{id}')
        rec.response.body == null
        rec.response.bodyInfo.kind == 'binary'
        rec.response.bodyInfo.filename == 'server-name.bin'
        rec.response.bodyInfo.contentType.contains('application/octet-stream')
        (rec.response.size as int) == 'RAWBYTES'.bytes.length
    }

    def "masks the Authorization request header: value -> ***, empty -> name only, absent -> omitted"() {
        given:
        open('GET-/widgets/{id}')

        expect: 'the core write-time sanitizer redacts before persisting'
        page.evaluate('''async () => {
            const m = await import('/apidocs/js/requestLog.js')
            const send = (opId, headers) => m.recordExecution({
                opId, req: { method: 'GET', absUrl: 'http://x/a', headers },
                response: { status: 200, statusText: 'OK', durationMs: 1, contentType: 'application/json', blob: new Blob(['{}']), rawBody: '{}', headersList: [] },
                snapshot: {}
            })
            await send('GET /auth-value', { Authorization: 'secret-token' })
            await send('GET /auth-empty', { Authorization: '' })
            await send('GET /auth-absent', { 'X-Other': 'y' })
            const v = (await m.recordsForOp('GET /auth-value'))[0].request.headers
            const e = (await m.recordsForOp('GET /auth-empty'))[0].request.headers
            const a = (await m.recordsForOp('GET /auth-absent'))[0].request.headers
            return { masked: v.Authorization, empty: e.Authorization, emptyPresent: 'Authorization' in e, absent: 'Authorization' in a }
        }''') == [masked: '***', empty: '', emptyPresent: true, absent: false]
    }

    def "never writes the Authorization token to disk (raw IndexedDB scan)"() {
        given:
        open('GET-/widgets/{id}')
        fillAuth('signature-supersecret')
        param('id').locator('.control input').fill('1')

        when:
        captureSend('**/widgets/**')
        waitForOneRecord('GET /widgets/{id}')

        then: 'the stored header is masked and the raw database never contains the token'
        records('GET /widgets/{id}')[0].request.headers.Authorization == '***'
        !rawDbContainsSecret('signature-supersecret')
    }

    def "runs extension sanitizers after the core default, in registration order"() {
        given:
        open('GET-/widgets/{id}')

        expect: 'the core mask runs first; two registered sanitizers then run, in the order registered'
        page.evaluate('''async () => {
            const m = await import('/apidocs/js/requestLog.js')
            m.registerSanitizer(r => { r.response.body = (r.response.body || '') + '#first'; return r })
            m.registerSanitizer(r => { r.response.body = (r.response.body || '') + '#second'; return r })
            await m.recordExecution({
                opId: 'GET /order',
                req: { method: 'GET', absUrl: 'http://x/a', headers: { Authorization: 'tok' } },
                response: { status: 200, statusText: 'OK', durationMs: 1, contentType: 'application/json', blob: new Blob(['{}']), rawBody: '{}', headersList: [] },
                snapshot: {}
            })
            const rec = (await m.recordsForOp('GET /order'))[0]
            return { auth: rec.request.headers.Authorization, body: rec.response.body }
        }''') == [auth: '***', body: '{}#first#second']
    }

    def "drops the record when an extension sanitizer throws (fail-closed), without breaking execution"() {
        given:
        open('GET-/widgets/{id}')

        expect: 'recordExecution neither throws nor persists when a sanitizer throws'
        page.evaluate('''async () => {
            const m = await import('/apidocs/js/requestLog.js')
            m.registerSanitizer(() => { throw new Error('boom') })
            let threw = false
            try {
                await m.recordExecution({
                    opId: 'GET /boom',
                    req: { method: 'GET', absUrl: 'http://x/a', headers: {} },
                    response: { status: 200, statusText: 'OK', durationMs: 1, contentType: 'application/json', blob: new Blob(['{}']), rawBody: '{}', headersList: [] },
                    snapshot: {}
                })
            } catch (e) { threw = true }
            return { threw, count: (await m.recordsForOp('GET /boom')).length }
        }''') == [threw: false, count: 0]
    }

    def "evicts the oldest beyond the per-operation cap, keeping the newest"() {
        given:
        open('GET-/widgets/{id}')

        when: 'thirty records are stored for one operation (cap is 25)'
        def r = page.evaluate('''async () => {
            const m = await import('/apidocs/js/requestLog.js')
            for (let i = 0; i < 30; i++) await m.recordExecution({
                opId: 'GET /capped',
                req: { method: 'GET', absUrl: 'http://x/' + i, headers: {} },
                response: { status: 200, statusText: 'OK', durationMs: 1, contentType: 'application/json', blob: new Blob(['' + i]), rawBody: '' + i, headersList: [] },
                snapshot: { n: i }
            })
            const recs = await m.recordsForOp('GET /capped')
            return { count: recs.length, oldestKept: recs[0].request.params.n, newestKept: recs[recs.length - 1].request.params.n }
        }''')

        then: 'only the newest 25 remain, oldest-first (entries 0..4 evicted)'
        (r.count as int) == 25
        (r.oldestKept as int) == 5
        (r.newestKept as int) == 29
    }

    def "evicts the globally-oldest beyond the global count cap"() {
        given:
        open('GET-/widgets/{id}')

        when: 'twenty-one operations each at the per-op cap exceed the 500 global cap (525 writes)'
        def total = page.evaluate('''async () => {
            const m = await import('/apidocs/js/requestLog.js')
            for (let op = 0; op < 21; op++) for (let i = 0; i < 25; i++) await m.recordExecution({
                opId: 'GET /op-' + op,
                req: { method: 'GET', absUrl: 'http://x', headers: {} },
                response: { status: 200, statusText: 'OK', durationMs: 1, contentType: 'application/json', blob: new Blob(['x']), rawBody: 'x', headersList: [] },
                snapshot: {}
            })
            return (await m.allRecords()).length
        }''')

        then: 'the store is bounded to the global cap'
        (total as int) == 500
    }

    def "evicts the oldest beyond the global byte cap"() {
        given:
        open('GET-/widgets/{id}')

        when: 'two ~3 MB records (via large replay snapshots) together exceed the ~5 MB byte cap'
        def r = page.evaluate('''async () => {
            const m = await import('/apidocs/js/requestLog.js')
            const write = (n) => m.recordExecution({
                opId: 'GET /heavy',
                req: { method: 'GET', absUrl: 'http://x/' + n, headers: {} },
                response: { status: 200, statusText: 'OK', durationMs: 1, contentType: 'application/json', blob: new Blob(['{}']), rawBody: '{}', headersList: [] },
                snapshot: { n, big: 'x'.repeat(3000000) }
            })
            await write(1)
            await write(2)
            const recs = await m.recordsForOp('GET /heavy')
            return { count: recs.length, keptN: recs.length ? recs[recs.length - 1].request.params.n : null }
        }''')

        then: 'the oldest is evicted to stay under the byte cap; the newest remains'
        (r.count as int) == 1
        (r.keptN as int) == 2
    }

    def "disables gracefully when IndexedDB is unavailable"() {
        given: 'no operation selected, so the Request Log panel never mounts and the store stays unopened'
        open()

        when: 'IndexedDB is shadowed before the store opens its database'
        def r = page.evaluate('''async () => {
            Object.defineProperty(window, 'indexedDB', { configurable: true, value: undefined })
            const m = await import('/apidocs/js/requestLog.js')
            let threw = false
            try {
                await m.recordExecution({
                    opId: 'GET /x', req: { method: 'GET', absUrl: 'http://x', headers: {} },
                    response: { status: 200, statusText: 'OK', durationMs: 1, contentType: 'application/json', blob: new Blob(['x']), rawBody: 'x', headersList: [] },
                    snapshot: {}
                })
            } catch (e) { threw = true }
            return { threw, recs: (await m.recordsForOp('GET /x')).length }
        }''')

        then: 'capture neither throws nor persists, and reads return empty'
        r.threw == false
        (r.recs as int) == 0
    }

}
