package org.plukh.spyglass.spring.webmvc.browser

import com.microsoft.playwright.Page

/**
 * Fit-to-content on the sidebar divider: double-clicking it — or pressing "f" while it's focused —
 * re-runs the widest-operation measurement (the same one that sets the default width), so a manually
 * widened sidebar snaps back to the narrowest width that doesn't clip the operation rows. The failure
 * signal is a horizontal scrollbar on the list (scrollWidth > clientWidth), so the checks assert the
 * list has no horizontal overflow after fitting. (High-DPI + scrolling-list correctness is covered by
 * the demo module's SidebarFitAE, which drives a spec long enough to force a vertical scrollbar.)
 */
class SidebarFitAE extends SpyglassSpecBase {

    def "double-clicking the divider fits the sidebar to the widest operation"() {
        given: 'the sidebar loads at its measured fit width, then the user widens it to the maximum'
        open()
        double fit = sidebarWidth(page)
        page.locator('.divider').focus()
        page.keyboard().press('End')
        waitForWidth("w => w > ${fit}")
        double wide = sidebarWidth(page)

        when: 'double-clicking the divider'
        page.locator('.divider').dblclick()

        then: 'the sidebar returns to the fit width and no operation row is clipped'
        waitForWidth("w => w < ${wide}")
        assert Math.abs(sidebarWidth(page) - fit) <= 1
        assert overflowPx(page) <= 0.5
    }

    def "pressing 'f' on the focused divider fits the sidebar"() {
        given:
        open()
        double fit = sidebarWidth(page)
        page.locator('.divider').focus()
        page.keyboard().press('End')
        waitForWidth("w => w > ${fit}")

        when:
        page.keyboard().press('f')

        then:
        waitForWidth("w => Math.abs(w - ${fit}) <= 1")
        assert overflowPx(page) <= 0.5
    }

    /** The sidebar's current width, read from the inline style the component controls (not a rendered box). */
    private static double sidebarWidth(Page p) {
        p.evaluate("() => parseFloat(document.querySelector('.sidebar').style.width)") as double
    }

    /** Waits until the sidebar's inline width satisfies the given JS predicate (absorbs the re-render). */
    private void waitForWidth(String predicate) {
        page.waitForFunction("() => { const w = parseFloat(document.querySelector('.sidebar').style.width); return (${predicate})(w); }")
    }

    /** The list's horizontal overflow (scrollWidth - clientWidth) — > 0 means a horizontal scrollbar. */
    private static double overflowPx(Page p) {
        p.evaluate("() => { const l = document.querySelector('.op-list'); return l.scrollWidth - l.clientWidth; }") as double
    }
}
