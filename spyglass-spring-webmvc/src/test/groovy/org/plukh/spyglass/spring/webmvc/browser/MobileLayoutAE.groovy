package org.plukh.spyglass.spring.webmvc.browser

import com.microsoft.playwright.Browser
import com.microsoft.playwright.Page

import static com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat

/**
 * Responsive / narrow-viewport layout (#17). Below the single 768px breakpoint the two-column shell
 * collapses to one column and the operation list becomes an off-canvas drawer, driven purely by CSS +
 * a matchMedia flag (same Sidebar DOM, no separate component tree). These specs assert the browse/read
 * experience on a phone-sized viewport (390x844): the single-column stack order, the drawer
 * open/select/close cycle with focus trapping, and that the response stays reachable without blowing
 * out the viewport width. A final spec pins that the desktop layout above the breakpoint is unchanged.
 */
class MobileLayoutAE extends SpyglassSpecBase {

    /** A representative phone viewport (iPhone 12/13/14 logical size). */
    private static final int NARROW_W = 390
    private static final int NARROW_H = 844

    def "collapses to a single column with an operation drawer below the breakpoint"() {
        when:
        openNarrow()

        then: 'the narrow layout is active with the drawer closed'
        assertThat(page.locator('.layout.narrow')).hasCount(1)
        page.locator('.layout.drawer-open').count() == 0

        and: 'the stacked-header trigger is shown and the resizer is hidden'
        assertThat(page.locator('.drawer-trigger')).isVisible()
        assertThat(page.locator('.divider')).isHidden()

        and: 'the off-canvas sidebar is not a modal dialog while closed (nothing walled off from AT)'
        page.locator('.sidebar[role="dialog"]').count() == 0
        page.locator('.sidebar[aria-modal="true"]').count() == 0

        and: 'with nothing selected, the trigger prompts to browse operations'
        assertThat(page.locator('.drawer-trigger .drawer-trigger-label')).hasText('Select an operation')
    }

    def "stacks the header trigger, request settings and operation panel top to bottom"() {
        given:
        openNarrow('GET-/widgets/{id}')

        when: 'the request-settings disclosure is expanded so the whole stack is present'
        page.locator('.settings-toggle').click()

        then: 'the regions read top-to-bottom in the agreed single-column order'
        List tops = page.evaluate('''() => {
          const top = s => { const el = document.querySelector(s); return el ? el.getBoundingClientRect().top : null; };
          return [top('.drawer-trigger'), top('.settings-toggle'), top('.accept-field'), top('.headers-editor'), top('.op-panel')];
        }''') as List
        tops.every { it != null }
        tops == tops.toSorted()
    }

    def "collapses the request settings behind a base-URL disclosure, revealed on demand"() {
        given:
        openNarrow('GET-/widgets/{id}')

        expect: 'collapsed by default: the disclosure shows the base URL, the Accept/headers block is hidden'
        assertThat(page.locator('.settings-toggle')).isVisible()
        assertThat(page.locator('.settings-toggle-val')).containsText('http')
        assertThat(page.locator('.accept-field')).isHidden()

        when: 'the disclosure is expanded'
        page.locator('.settings-toggle').click()

        then: 'the Accept and headers controls become visible'
        assertThat(page.locator('.accept-field')).isVisible()
        assertThat(page.locator('.headers-editor')).isVisible()
    }

    def "pins the Send button to the bottom of the viewport on mobile"() {
        given:
        openNarrow('GET-/widgets/{id}')

        expect: 'the Send control is fixed flush to the bottom of the viewport'
        Object s = page.evaluate('''() => {
          const el = document.querySelector('.send-cta'); const cs = getComputedStyle(el); const r = el.getBoundingClientRect();
          return { pos: cs.position, gap: Math.round(window.innerHeight - r.bottom) };
        }''')
        s['pos'] == 'fixed'
        (s['gap'] as int) <= 1
    }

    def "opens the drawer, selects an operation, then closes and reveals the panel"() {
        given:
        openNarrow()

        when: 'tapping the header trigger'
        page.locator('.drawer-trigger').click()

        then: 'the drawer and its scrim open, it becomes a modal dialog, and the filter takes focus'
        assertThat(page.locator('.layout.drawer-open')).hasCount(1)
        assertThat(page.locator('.drawer-scrim.open')).hasCount(1)
        assertThat(page.locator('.sidebar[role="dialog"][aria-modal="true"]')).hasCount(1)
        assertThat(page.locator('.sidebar .filter')).isFocused()

        when: 'choosing an operation from the list'
        def link = page.locator('.sidebar .op-link').first()
        def path = link.locator('.op-path').textContent()
        link.click()

        then: 'the drawer closes, its panel opens and is scrolled into view'
        assertThat(page.locator('.layout.drawer-open')).hasCount(0)
        assertThat(page.locator('.op-panel .op-header .op-path')).hasText(path)
        assertThat(page.locator('.op-panel')).isInViewport()

        and: 'the trigger now reflects the selected operation'
        assertThat(page.locator('.drawer-trigger .drawer-trigger-label')).hasText(path)
    }

    def "dismisses the drawer via the scrim, the close control, and Escape"() {
        given:
        openNarrow()

        when: 'the scrim is tapped in the area outside the drawer'
        page.locator('.drawer-trigger').click()
        assertThat(page.locator('.layout.drawer-open')).hasCount(1)
        page.mouse().click(NARROW_W - 10, NARROW_H / 2 as int)   // right of the <=360px-wide drawer

        then:
        assertThat(page.locator('.layout.drawer-open')).hasCount(0)

        when: 'the in-drawer close control is used'
        page.locator('.drawer-trigger').click()
        page.locator('.drawer-close').click()

        then:
        assertThat(page.locator('.layout.drawer-open')).hasCount(0)

        when: 'Escape is pressed while the drawer is open'
        page.locator('.drawer-trigger').click()
        page.keyboard().press('Escape')

        then: 'the drawer closes and focus returns to the trigger'
        assertThat(page.locator('.layout.drawer-open')).hasCount(0)
        assertThat(page.locator('.drawer-trigger')).isFocused()
    }

    def "traps focus within the open drawer"() {
        given:
        openNarrow()
        page.locator('.drawer-trigger').click()
        assertThat(page.locator('.sidebar .filter')).isFocused()

        when: 'tabbing forward well past the last control'
        10.times { page.keyboard().press('Tab') }

        then: 'focus never escapes the drawer'
        inDrawer()

        when: 'shift-tabbing back past the first control'
        14.times { page.keyboard().press('Shift+Tab') }

        then: 'focus is still trapped inside the drawer'
        inDrawer()
    }

    def "keeps the response and its controls reachable without horizontal overflow"() {
        given:
        openNarrow('GET-/widgets/{id}')
        param('id').locator('.control input').fill('1')

        when:
        captureSend('**/widgets/**')

        then: 'the response and its raw/pretty + download controls render reachable'
        assertThat(page.locator('.response')).isVisible()
        assertThat(page.locator('.resp-toolbar button', new Page.LocatorOptions().setHasText('Download'))).isVisible()

        and: 'the page has not blown out its width (min-width:0 / overflow safety holds)'
        double overflow = page.evaluate('() => document.documentElement.scrollWidth - document.documentElement.clientWidth') as double
        overflow <= 1
    }

    def "a pointer tap does not raise a lingering focus tooltip"() {
        given:
        openNarrow('POST-/widgets')
        page.waitForSelector('.op-panel')

        when: 'tapping an optional field include checkbox that carries a focus tooltip'
        page.locator('.field-include input.include').first().tap()
        page.waitForTimeout(400)   // well past the 250ms tooltip show delay

        then: 'no tooltip is shown — focus arrived from a touch tap, not the keyboard'
        page.locator('.app-tooltip.visible').count() == 0
    }

    def "leaves the desktop two-column layout unchanged above the breakpoint"() {
        given: 'the default (wide) viewport — no narrow treatment'
        open('GET-/widgets/{id}')

        expect: 'no narrow class, no drawer trigger / settings disclosure, and the resizer is present'
        page.locator('.layout.narrow').count() == 0
        page.locator('.drawer-trigger').count() == 0
        page.locator('.settings-toggle').count() == 0
        assertThat(page.locator('.divider')).isVisible()

        and: 'the request settings show in-flow and Send is not pinned'
        assertThat(page.locator('.base-url')).isVisible()
        (page.evaluate("() => getComputedStyle(document.querySelector('.send-cta')).position") as String) != 'fixed'

        and: 'the sidebar keeps its inline, resizable width (an in-flow column)'
        (page.evaluate("() => document.querySelector('.sidebar').style.width") as String) != ''
    }

    // ---- helpers -------------------------------------------------------------

    /**
     * Loads the explorer at a phone-sized viewport, in a touch-enabled context so specs can drive real
     * taps ({@code tap()}). Resizing the live page (rather than pre-sizing via NewContextOptions) is
     * deliberate: only a real resize makes headless Chromium's width media features report the emulated
     * viewport, so the app's matchMedia('(max-width: 768px)') actually matches. The base setup()'s
     * default-viewport context is swapped out (and closed) so the base helpers and cleanup() use this one.
     */
    private Page openNarrow(String opHash = '') {
        context.close()
        context = browser.newContext(new Browser.NewContextOptions()
                .setBaseURL("http://localhost:${port}")
                .setAcceptDownloads(true)
                .setPermissions(['clipboard-read', 'clipboard-write'])
                .setHasTouch(true))
        page = context.newPage()
        page.setViewportSize(NARROW_W, NARROW_H)
        open(opHash)
    }

    /** Whether the currently focused element is inside the drawer (the reused sidebar). */
    private boolean inDrawer() {
        page.evaluate("() => !!(document.activeElement && document.activeElement.closest('.sidebar'))") as boolean
    }
}
