package org.plukh.spyglass.demo.browser

import com.microsoft.playwright.Browser

/**
 * Regression for the sidebar fit-to-content measurement on a high-DPI (OS-scaled) display with a
 * scrolling operation list — the demo's many operations force a vertical scrollbar. The fit must clear
 * both the scrollbar's width and the sub-pixel accumulation of glyph positions at a fractional
 * devicePixelRatio, neither of which the unconstrained ideal measurement can see. The failure signal
 * is a horizontal scrollbar on the list (scrollWidth > clientWidth).
 */
class SidebarFitAE extends SpyglassDemoSpecBase {

    def "fit clears the scrollbar and sub-pixel rounding on a high-DPI display"() {
        given: 'a fractional device scale factor (e.g. a 4K laptop at 175% OS scaling)'
        def ctx = browser.newContext(new Browser.NewContextOptions()
                .setBaseURL("http://localhost:${port}")
                .setDeviceScaleFactor(1.75d)
                .setViewportSize(900, 760))
        def pg = ctx.newPage()

        when: 'the explorer loads and fits to content'
        pg.navigate('/apidocs/index.html')
        pg.waitForSelector('.sidebar .op-link')

        then: 'the operation list actually scrolls vertically (the scenario under test) and nothing is clipped'
        assert pg.evaluate("() => { const l = document.querySelector('.op-list'); return l.scrollHeight > l.clientHeight; }")
        assert overflowPx(pg) <= 0.5

        when: 'the sidebar is widened and fitted again via double-click'
        pg.locator('.divider').focus()
        pg.keyboard().press('End')
        pg.locator('.divider').dblclick()

        then:
        assert overflowPx(pg) <= 0.5

        cleanup:
        ctx?.close()
    }

    /** The list's horizontal overflow (scrollWidth - clientWidth) — > 0 means a horizontal scrollbar. */
    private static double overflowPx(com.microsoft.playwright.Page p) {
        p.evaluate("() => { const l = document.querySelector('.op-list'); return l.scrollWidth - l.clientWidth; }") as double
    }
}
