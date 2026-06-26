package org.plukh.spyglass.spring.webmvc.browser

import com.microsoft.playwright.Route
import org.springframework.core.io.ClassPathResource

import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicReference
import java.util.function.Consumer

import static com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat

/**
 * Spec loading and sidebar navigation: title from the spec, operations grouped by tag, the live
 * filter, deep-linking via the URL hash, and erasing of invalid anchors.
 */
class NavigationAE extends SpyglassSpecBase {

    def "loads the spec and titles the page from info.title"() {
        when:
        open()

        then:
        page.locator('.brand').textContent() == 'Fixture Explorer API'
        page.title() == 'Fixture Explorer API'
    }

    def "lists every operation grouped by tag, sorted"() {
        when:
        open()

        then:
        page.locator('.op-link').count() == 17
        page.locator('.tag-name').allTextContents() == ['Action items', 'Bodies', 'Composed', 'Legacy', 'Lookups', 'Polymorphic', 'Widgets']
    }

    def "shows a loading state in the sidebar until the spec resolves (not 'No operations match')"() {
        given: 'the spec response is held open so the pre-load window is observable'
        def held = new AtomicReference<Route>()
        page.route('**/v3/api-docs', ({ Route route -> held.set(route) } as Consumer<Route>))

        when: 'the explorer is opened while the spec is still in flight'
        page.navigate('/apidocs/index.html')
        page.waitForSelector('.op-list .hint')

        then: 'the sidebar shows the loading hint, not the empty-results message'
        page.locator('.op-list .hint').textContent().contains('Loading spec')
        page.locator('.op-link').count() == 0

        when: 'the spec finally resolves'
        awaitRoute(held).fulfill(new Route.FulfillOptions().setStatus(200)
                .setContentType('application/json').setBody(specBody()))
        page.waitForSelector('.sidebar .op-link')

        then: 'the loading hint is gone and the operations are listed'
        page.locator('.op-list .hint').count() == 0
        page.locator('.op-link').count() == 17
    }

    def "filters live into field-named match sections, restoring tag groups when cleared"() {
        given:
        open()

        when: 'a query that matches a single operation by its path'
        page.locator('.filter').fill('things')

        then: 'tag grouping gives way to a single "In path" section'
        page.locator('.tag-name').count() == 0
        page.locator('.match-name').allTextContents() == ['In path']
        page.locator('.op-link').count() == 1
        page.locator('.op-link.m-post .op-path').textContent() == '/things'

        when: 'a query that matches nothing'
        page.locator('.filter').fill('nonsense-xyz')

        then:
        page.locator('.op-link').count() == 0
        page.locator('.op-list .hint').textContent() == 'No operations match.'

        when: 'the filter is cleared'
        page.locator('.filter').fill('')

        then: 'the tag-grouped view returns unchanged'
        page.locator('.match-name').count() == 0
        page.locator('.tag-name').count() == 7
    }

    def "ranks matches into sections by matched field, in precedence order"() {
        given:
        open()

        when: 'a term that matches some operations by path and one only by summary'
        page.locator('.filter').fill('widget')

        then: 'two sections appear, path ranked above summary'
        page.locator('.match-name').allTextContents() == ['In path', 'In summary']

        and: 'the path section holds the two /widgets operations, in spec order'
        page.locator(".match-group:has(.match-name:text-is('In path')) .op-link .op-path").allTextContents() == ['/widgets', '/widgets/{id}']

        and: 'the summary section holds the operation whose only match is its summary'
        page.locator(".match-group:has(.match-name:text-is('In summary')) .op-link .op-path").allTextContents() == ['/composed']
    }

    def "highlights the matched text in place and explains a summary match with a snippet"() {
        given:
        open()

        when:
        page.locator('.filter').fill('widget')

        then: 'a path match is highlighted in place within the path text'
        page.locator('.op-link .op-path mark').first().textContent() == 'widget'

        and: 'the summary match shows a second-line snippet with the match highlighted'
        def snippet = page.locator(".match-group:has(.match-name:text-is('In summary')) .op-snippet")
        snippet.textContent() == 'Create a composed widget'
        snippet.locator('mark').textContent() == 'widget'
    }

    def "windows a long summary around a late match once the column is too narrow to show it whole"() {
        given: 'a late match deep in the longest summary in the fixture'
        open()
        page.locator('.filter').fill('additional')

        expect: 'bucketed under In summary'
        page.locator('.match-name').allTextContents() == ['In summary']

        when: 'the sidebar is narrowed so the summary no longer fits — the budget follows the width'
        narrowSidebarToMinimum()

        then: 'the snippet windows onto the match: a leading ellipsis, with the match still highlighted'
        page.waitForFunction("() => { const s = document.querySelector('.op-snippet'); return !!s && s.textContent.startsWith('…'); }")
        page.locator('.op-snippet mark').textContent() == 'additional'
    }

    def "shows a short summary whole even when the match is near its end (no needless head-trim)"() {
        given:
        open()

        when: 'a term that matches late inside short summaries'
        page.locator('.filter').fill('anyof')

        then: 'the summaries are shown whole — not windowed with a leading ellipsis'
        page.locator('.op-snippet').allTextContents().every { !it.startsWith('…') }
        page.locator('.op-snippet').allTextContents().contains('Submit a measure (scalar anyOf)')
    }

    def "a long summary snippet ellipsizes within the column instead of widening it"() {
        given:
        open()

        when: 'an early match in the long summary — shown whole, no leading ellipsis'
        page.locator('.filter').fill('a config')

        then: 'the whole summary is present in the DOM (no JS truncation), highlighted'
        page.locator('.op-snippet').textContent() == 'Save a config envelope (fixed properties + additionalProperties)'
        page.locator('.op-snippet mark').textContent() == 'a config'

        when: 'the sidebar is narrowed so the long snippet no longer fits the column'
        narrowSidebarToMinimum()

        then: 'the snippet is clipped by CSS, and it has NOT given the list a horizontal scroll (width:0 keeps it from widening the column)'
        Map geom = page.evaluate('''() => {
          const snip = document.querySelector('.op-snippet');
          const list = document.querySelector('.op-list');
          return { clipped: snip.scrollWidth > snip.clientWidth, listOverflow: list.scrollWidth - list.clientWidth };
        }''') as Map
        geom.clipped == true
        geom.listOverflow <= 1
    }

    def "buckets an operationId-only match under In operation ID and shows the full id"() {
        given:
        open()

        when: 'a substring that appears only in an operationId'
        page.locator('.filter').fill('saveconfig')

        then:
        page.locator('.match-name').allTextContents() == ['In operation ID']
        page.locator('.op-link').count() == 1

        and: 'the full operationId is shown (not windowed) with the match highlighted'
        page.locator('.op-snippet').textContent() == 'saveConfigEnvelope'
        page.locator('.op-snippet mark').textContent() == 'saveConfig'
    }

    def "deep-links to an operation via the URL hash"() {
        when:
        open('POST-/widgets')

        then:
        page.locator('.op-header .method').textContent() == 'POST'
        page.locator('.op-header .op-path').textContent() == '/widgets'
        page.locator('.op-link.active .op-path').textContent() == '/widgets'
    }

    def "erases an invalid anchor and shows the placeholder"() {
        when:
        page.navigate('/apidocs/index.html#GET-/does-not-exist')
        page.waitForSelector('.status-msg')

        then:
        page.locator('.op-panel').count() == 0
        page.locator('.status-msg').textContent().contains('Select an operation')
        !page.url().contains('#')
    }

    def "shows the operation ID on the panel only when the spec declares one"() {
        when: 'opening an operation that declares an operationId'
        open('POST-/config')

        then: 'a labelled, copyable Operation ID line shows the id'
        page.locator('.op-id-label').textContent() == 'Operation ID'
        page.locator('.op-id-value').textContent() == 'saveConfigEnvelope'
        page.locator('.op-id-copy').isVisible()

        when: 'opening an operation that declares none'
        open('POST-/things')

        then: 'no Operation ID line is rendered'
        page.locator('.op-id').count() == 0
    }

    /**
     * Sidebar layout regression: when the divider is dragged in so a long endpoint path is wider than
     * the visible column, the selected row's highlight must still enclose the whole path. The tag-group
     * is sized to its widest row ({@code min-width: 100%; width: max-content}), so the active op-link —
     * and the outline that marks it selected — grows to wrap the full path instead of being clipped.
     */
    def "the selected row's outline encloses a path wider than the narrowed sidebar"() {
        given: 'the long-path operation is selected while the sidebar is at its measured (wide) default'
        open()
        page.locator(".op-link:has-text('refund-request')").click()
        page.waitForSelector('.op-link.active')

        when: 'the divider is dragged in, forcing the selected path to overflow the visible column'
        narrowSidebarToMinimum()
        Map geom = sidebarGeometry()

        then: 'this is genuinely the overflow case — the path extends past the visible list (fix-independent)'
        geom.pathRight > geom.listRight

        and: 'the active row fully contains its path: the outline is not clipped before the text ends'
        geom.linkRight + 1 >= geom.pathRight
    }

    // ---- keyboard navigation -------------------------------------------------

    def "shows the explorer branding footer with a GitHub link"() {
        given:
        open()

        expect: 'the Spyglass wordmark and blurb, distinct from the API title'
        page.locator('.sidebar-foot .foot-brand').textContent().contains('Spyglass')
        page.locator('.sidebar-foot .foot-brand').textContent().contains('OpenAPI Explorer')

        and: 'a GitHub link that opens the repo in a new tab'
        page.locator('.sidebar-foot .foot-link').getAttribute('href') == 'https://github.com/vdenisov/spyglass'
        page.locator('.sidebar-foot .foot-link').getAttribute('target') == '_blank'

        and: 'the build-injected version is shown (value not asserted — it changes per release)'
        page.locator('.sidebar-foot .foot-version').isVisible()
    }

    def "no operation is highlighted on a passive load (nothing looks selected)"() {
        given:
        open()

        expect: 'a fresh load with no selection shows no highlight, matching the empty panel'
        page.locator('.op-link.active').count() == 0
        page.locator('.op-list.kb-active').count() == 0
        page.locator('.status-msg').textContent().contains('Select an operation')

        when: 'the filter is focused — the sidebar becomes the active keyboard region'
        page.keyboard().press('/')

        then: 'the list is keyboard-active and the first operation is the cursor'
        page.locator('.op-list.kb-active').count() == 1
        page.locator('.op-link.active').count() == 1
    }

    def "the / shortcut focuses the operation filter"() {
        given:
        open()

        when:
        page.keyboard().press('/')

        then:
        page.evaluate("() => document.activeElement.classList.contains('filter')") == true
    }

    def "the / shortcut is ignored while typing in a field"() {
        given:
        open()
        page.locator('.filter').click()

        when:
        page.keyboard().press('/')

        then: 'the slash is typed into the field, not hijacked as a shortcut'
        page.locator('.filter').inputValue() == '/'
    }

    def "arrowing from the filter moves focus into the list and opens the focused op"() {
        given:
        open()
        page.keyboard().press('/')

        when: 'arrowing from the filter moves focus onto an op-link, which opens (selection follows focus)'
        page.keyboard().press('ArrowDown')
        def focused = page.locator('.op-link:focus .op-path').textContent()

        then: 'the focused row is the highlighted cursor immediately, and the panel opens it (debounced)'
        page.locator('.op-link.active .op-path').textContent() == focused
        assertThat(page.locator('.op-header .op-path')).hasText(focused)
    }

    def "the filter shows a clear button that empties it"() {
        given:
        open()

        expect: 'no clear button while the filter is empty'
        page.locator('.filter-clear').count() == 0

        when:
        page.locator('.filter').fill('widgets')

        then:
        page.locator('.filter-clear').isVisible()

        when:
        page.locator('.filter-clear').click()

        then:
        page.locator('.filter').inputValue() == ''
    }

    def "Escape clears the filter"() {
        given:
        open()
        page.locator('.filter').fill('widgets')

        when:
        page.locator('.filter').press('Escape')

        then:
        page.locator('.filter').inputValue() == ''
    }

    // ---- helpers -------------------------------------------------------------

    /** Waits until the (background-thread) route handler has captured the held spec request. */
    private Route awaitRoute(AtomicReference<Route> ref) {
        def deadline = System.currentTimeMillis() + 5000
        while (ref.get() == null && System.currentTimeMillis() < deadline) Thread.sleep(25)
        ref.get()
    }

    /** The OpenAPI fixture body the test app serves at /v3/api-docs. */
    private String specBody() {
        new ClassPathResource('apidocs-test/openapi-fixture.json').inputStream.getText(StandardCharsets.UTF_8.name())
    }

    /** Drags the resize divider to the far left; the component clamps the width to its minimum. */
    private void narrowSidebarToMinimum() {
        def box = page.locator('.divider').boundingBox()
        double cy = box.y + box.height / 2
        page.mouse().move(box.x + box.width / 2, cy)
        page.mouse().down()
        page.mouse().move(0, cy)
        page.mouse().up()
    }

    /** Right-edge coordinates for the active row, its path span and the visible scroll container. */
    private Map sidebarGeometry() {
        page.evaluate('''() => {
          const link = document.querySelector('.op-link.active');
          const path = link.querySelector('.op-path');
          const list = document.querySelector('.op-list');
          return {
            linkRight: link.getBoundingClientRect().right,
            pathRight: path.getBoundingClientRect().right,
            listRight: list.getBoundingClientRect().right
          };
        }''') as Map
    }
}
