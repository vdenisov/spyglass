package org.plukh.spyglass.spring.webmvc.browser

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
        page.locator('.op-link').count() == 15
        page.locator('.tag-name').allTextContents() == ['Action items', 'Bodies', 'Legacy', 'Lookups', 'Polymorphic', 'Widgets']
    }

    def "filters operations live"() {
        given:
        open()

        when:
        page.locator('.filter').fill('things')

        then:
        page.locator('.op-link').count() == 1
        page.locator('.tag-name').allTextContents() == ['Lookups']
        page.locator('.op-link.m-post .op-path').textContent() == '/things'

        when:
        page.locator('.filter').fill('nonsense-xyz')

        then:
        page.locator('.op-link').count() == 0
        page.locator('.op-list .hint').textContent() == 'No operations match.'
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

    def "shows a persistent keyboard-hint footer"() {
        given:
        open()

        expect:
        page.locator('.sidebar-hint').isVisible()
        page.locator('.sidebar-hint').textContent().contains('filter')
        page.locator('.sidebar-hint').textContent().contains('navigate')
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
