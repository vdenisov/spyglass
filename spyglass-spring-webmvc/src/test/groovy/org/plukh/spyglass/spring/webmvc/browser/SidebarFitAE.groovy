package org.plukh.spyglass.spring.webmvc.browser

/**
 * Fit-to-content on the sidebar divider: double-clicking it — or pressing "f" while it's focused —
 * re-runs the widest-operation measurement (the same one that sets the default width), so a manually
 * widened sidebar snaps back to the narrowest width that doesn't clip the operation rows.
 */
class SidebarFitAE extends SpyglassSpecBase {

    def "double-clicking the divider fits the sidebar to the widest operation"() {
        given: 'the sidebar loads at its measured fit width, then the user widens it to the maximum'
        open()
        double fit = sidebarWidth()
        page.locator('.divider').focus()
        page.keyboard().press('End')
        waitForWidth("w => w > ${fit}")
        double wide = sidebarWidth()

        when: 'double-clicking the divider'
        page.locator('.divider').dblclick()

        then: 'the sidebar returns to the fit width and no operation row is clipped'
        waitForWidth("w => w < ${wide}")
        assert Math.abs(sidebarWidth() - fit) <= 1
        assert !anyOpClipped()
    }

    def "pressing 'f' on the focused divider fits the sidebar"() {
        given:
        open()
        double fit = sidebarWidth()
        page.locator('.divider').focus()
        page.keyboard().press('End')
        waitForWidth("w => w > ${fit}")

        when:
        page.keyboard().press('f')

        then:
        waitForWidth("w => Math.abs(w - ${fit}) <= 1")
        assert !anyOpClipped()
    }

    /** The sidebar's current width, read from the inline style the component controls (not a rendered box). */
    private double sidebarWidth() {
        page.evaluate("() => parseFloat(document.querySelector('.sidebar').style.width)") as double
    }

    /** Waits until the sidebar's inline width satisfies the given JS predicate (absorbs the re-render). */
    private void waitForWidth(String predicate) {
        page.waitForFunction("() => { const w = parseFloat(document.querySelector('.sidebar').style.width); return (${predicate})(w); }")
    }

    private boolean anyOpClipped() {
        page.evaluate("() => Array.from(document.querySelectorAll('.op-link')).some(el => el.scrollWidth > el.clientWidth + 1)") as boolean
    }
}
