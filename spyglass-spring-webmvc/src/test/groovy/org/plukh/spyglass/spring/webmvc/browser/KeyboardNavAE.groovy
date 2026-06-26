package org.plukh.spyglass.spring.webmvc.browser

import static com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat

/**
 * Keyboard navigation consistency: the operation list is arrow-navigable both after a click and via
 * the filter ("/" → type → ↓ → Enter), with selection following focus; and the tab-like button groups
 * (Try/Schema, Form/Raw JSON) move with ←/→. These cover the gap where, after clicking an op, ↑/↓ used
 * to scroll the viewport instead of moving between operations.
 */
class KeyboardNavAE extends SpyglassSpecBase {

    def "after clicking an op, ArrowDown opens the next op (selection follows focus)"() {
        given:
        open()
        def links = page.locator('.sidebar .op-link')
        def firstPath = links.nth(0).locator('.op-path').textContent()
        def secondPath = links.nth(1).locator('.op-path').textContent()

        when: 'clicking the first op'
        links.nth(0).click()

        then: 'its panel opens'
        assertThat(page.locator('.op-panel .op-header .op-path')).hasText(firstPath)

        when: 'pressing ArrowDown — focus is on the clicked op'
        page.keyboard().press('ArrowDown')

        then: 'the next op opens (after the debounce) and is marked selected'
        assertThat(page.locator('.op-panel .op-header .op-path')).hasText(secondPath)
        assertThat(page.locator('.sidebar .op-link[aria-selected="true"] .op-path')).hasText(secondPath)
    }

    def "Home and End jump to the first and last op"() {
        given:
        open()
        def links = page.locator('.sidebar .op-link')
        def n = links.count()
        def firstPath = links.nth(0).locator('.op-path').textContent()
        def lastPath = links.nth(n - 1).locator('.op-path').textContent()
        links.nth(0).click()

        when:
        page.keyboard().press('End')

        then:
        assertThat(page.locator('.op-panel .op-header .op-path')).hasText(lastPath)

        when:
        page.keyboard().press('Home')

        then:
        assertThat(page.locator('.op-panel .op-header .op-path')).hasText(firstPath)
    }

    def "'/' focuses the filter and arrowing into the list opens the op"() {
        given:
        open()

        when: "pressing '/'"
        page.keyboard().press('/')

        then: 'the filter is focused'
        assertThat(page.locator('.sidebar .filter')).isFocused()

        when: 'filtering to a single op and arrowing into the list'
        page.locator('.sidebar .filter').fill('/measure')
        page.keyboard().press('ArrowDown')

        then: 'focus moves onto the op-link and it opens (selection follows focus, no Enter needed)'
        assertThat(page.locator('.sidebar .op-link:focus')).hasCount(1)
        assertThat(page.locator('.op-panel .op-header .op-path')).hasText('/measure')
    }

    def "arrow navigation walks the ranked match sections top to bottom"() {
        given: 'a filter that produces two sections (In path, then In summary)'
        open()
        page.keyboard().press('/')
        page.locator('.sidebar .filter').fill('widget')

        when: 'arrowing down from the filter enters the list at the first ranked row'
        page.keyboard().press('ArrowDown')

        then: 'the first path-section op opens'
        assertThat(page.locator('.op-panel .op-header .op-path')).hasText('/widgets')

        when: 'continuing down crosses out of the path section into the summary section'
        page.keyboard().press('ArrowDown')
        page.keyboard().press('ArrowDown')

        then: 'the summary-section op is reached last, in flat order across sections'
        assertThat(page.locator('.op-panel .op-header .op-path')).hasText('/composed')
        assertThat(page.locator('.sidebar .op-link:focus .op-path')).hasText('/composed')
    }

    def "the list shows its keyboard-active state only while it holds focus"() {
        given:
        open()

        expect: 'not keyboard-active on a passive load'
        page.locator('.op-list.kb-active').count() == 0

        when: 'clicking an op gives the list keyboard focus'
        page.locator('.sidebar .op-link').nth(0).click()

        then:
        assertThat(page.locator('.op-list.kb-active')).hasCount(1)

        when: 'focus leaves the sidebar for the panel'
        page.locator('.op-header').click()

        then: 'the list drops the keyboard-active state (its selection goes muted)'
        assertThat(page.locator('.op-list.kb-active')).hasCount(0)
    }

    def "Escape in the list returns focus to the filter"() {
        given:
        open()
        page.locator('.sidebar .op-link').nth(0).click()

        when:
        page.keyboard().press('Escape')

        then:
        assertThat(page.locator('.sidebar .filter')).isFocused()
    }

    def "clicking empty sidebar space keeps the list keyboard-navigable"() {
        given: 'a short filtered list, so the op-list has empty space below the rows'
        open()
        page.locator('.sidebar .filter').fill('widgets')
        def links = page.locator('.sidebar .op-link')
        links.first().click()
        def secondPath = links.nth(1).locator('.op-path').textContent()

        when: 'clicking the empty area at the bottom of the op-list (not a row)'
        def box = page.locator('.op-list').boundingBox()
        page.mouse().click(box.x + 12, box.y + box.height - 6)

        then: 'the list keeps keyboard focus'
        assertThat(page.locator('.op-list.kb-active')).hasCount(1)

        when: 'arrowing still moves between operations'
        page.keyboard().press('ArrowDown')

        then:
        assertThat(page.locator('.op-panel .op-header .op-path')).hasText(secondPath)
    }

    def "ArrowRight switches the Try it out / Schema tabs"() {
        given:
        open('POST-/widgets')

        expect: 'Try it out is selected initially'
        assertThat(page.locator('.op-tabs button[aria-selected="true"]')).hasText('Try it out')

        when: 'focusing the active tab and pressing ArrowRight'
        page.locator('.op-tabs button').first().focus()
        page.keyboard().press('ArrowRight')

        then: 'Schema becomes selected and its panel shows'
        assertThat(page.locator('.op-tabs button[aria-selected="true"]')).hasText('Schema')
        assertThat(page.locator('.schema-doc')).isVisible()
    }

    def "ArrowRight switches the Form / Raw JSON body tabs"() {
        given:
        open('POST-/widgets')

        expect: 'Form is selected initially'
        assertThat(page.locator('.body-head .body-tabs button[aria-selected="true"]')).hasText('Form')

        when:
        page.locator('.body-head .body-tabs button').first().focus()
        page.keyboard().press('ArrowRight')

        then: 'Raw JSON becomes selected and the editor mounts'
        assertThat(page.locator('.body-head .body-tabs button[aria-selected="true"]')).hasText('Raw JSON')
        assertThat(rawEditor()).isVisible()
    }
}
