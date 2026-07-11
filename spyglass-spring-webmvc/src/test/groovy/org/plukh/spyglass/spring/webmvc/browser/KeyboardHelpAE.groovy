package org.plukh.spyglass.spring.webmvc.browser

import static com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat

/**
 * The keyboard-shortcuts help overlay (issue #28): "?" — or the topbar control — opens a modal listing
 * the shortcuts, and Escape, the backdrop and the ✕ each close it. Also covers the reusable dialog's
 * focus handling (focus moves in on open and is restored to the trigger on close, and Tab stays
 * trapped inside) and the typing guard that keeps "?" from opening the overlay while a field is focused.
 */
class KeyboardHelpAE extends SpyglassSpecBase {

    def "'?' opens the help overlay listing the shortcuts"() {
        given:
        open()

        when:
        page.keyboard().press('?')

        then: 'the dialog is shown and lists a known shortcut'
        assertThat(page.locator('.modal[role="dialog"]')).isVisible()
        assertThat(page.locator('.modal')).containsText('Focus the operation filter')
    }

    def "the topbar '?' control opens the overlay"() {
        given:
        open()

        when:
        page.locator('.btn-help').click()

        then:
        assertThat(page.locator('.modal[role="dialog"]')).isVisible()
    }

    def "Escape closes the overlay"() {
        given:
        open()
        page.keyboard().press('?')
        assertThat(page.locator('.modal')).isVisible()

        when:
        page.keyboard().press('Escape')

        then:
        assertThat(page.locator('.modal')).hasCount(0)
    }

    def "the close button closes the overlay"() {
        given:
        open()
        page.keyboard().press('?')

        when:
        page.locator('.modal-close').click()

        then:
        assertThat(page.locator('.modal')).hasCount(0)
    }

    def "clicking the backdrop closes the overlay"() {
        given:
        open()
        page.keyboard().press('?')

        when: 'clicking the backdrop margin, outside the centered panel'
        def box = page.locator('.modal-backdrop').boundingBox()
        page.mouse().click(box.x + 5, box.y + 5)

        then:
        assertThat(page.locator('.modal')).hasCount(0)
    }

    def "opening moves focus into the dialog and closing restores it to the trigger"() {
        given:
        open()

        when: 'opening via the topbar control'
        page.locator('.btn-help').click()

        then: 'focus moves into the dialog'
        assertThat(page.locator('.modal')).isFocused()

        when: 'closing with Escape'
        page.keyboard().press('Escape')

        then: 'focus returns to the control that opened it'
        assertThat(page.locator('.btn-help')).isFocused()
    }

    def "Tab keeps focus trapped inside the dialog"() {
        given:
        open()
        page.keyboard().press('?')

        when: 'tabbing forward from the dialog'
        page.keyboard().press('Tab')

        then: 'focus lands on the close button (the only focusable) and stays there on further Tabs'
        assertThat(page.locator('.modal-close')).isFocused()

        when:
        page.keyboard().press('Tab')

        then:
        assertThat(page.locator('.modal-close')).isFocused()
    }

    def "'?' does not open the overlay while typing in the filter"() {
        given:
        open()

        when: 'the filter is focused and "?" is typed into it'
        page.locator('.sidebar .filter').fill('wid')
        page.keyboard().press('?')

        then: 'no dialog opens'
        assertThat(page.locator('.modal')).hasCount(0)
    }
}
