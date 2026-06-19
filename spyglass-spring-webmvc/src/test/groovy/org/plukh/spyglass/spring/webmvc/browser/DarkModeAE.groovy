package org.plukh.spyglass.spring.webmvc.browser

import com.microsoft.playwright.Locator
import com.microsoft.playwright.Page
import com.microsoft.playwright.options.ColorScheme

/**
 * Theme switching: the explorer follows the OS color scheme by default and the Light / System / Dark
 * toggle forces a choice that overrides the OS and persists across reloads (per browser, via
 * localStorage). Also covers that the components which don't key off the shared CSS variables for
 * free — the body-tab buttons and the CodeMirror raw-JSON editor — render legibly in dark mode.
 */
class DarkModeAE extends SpyglassSpecBase {

    private static final String DARK_BG = 'rgb(13, 17, 23)'        // --bg #0d1117
    private static final String DARK_BG_ALT = 'rgb(22, 27, 34)'    // --bg-alt #161b22
    private static final String DARK_TEXT = 'rgb(230, 237, 243)'   // --text #e6edf3
    private static final String LIGHT_BG = 'rgb(255, 255, 255)'    // --bg #ffffff

    // Brightened, differentiated dark syntax colors.
    private static final String DARK_STRING = 'rgb(255, 123, 114)' // --cm-string #ff7b72
    private static final String DARK_NUMBER = 'rgb(126, 231, 135)' // --cm-number #7ee787
    private static final String DARK_BOOL = 'rgb(121, 192, 255)'   // --cm-bool #79c0ff
    private static final String DARK_NULL = 'rgb(210, 168, 255)'   // --cm-null #d2a8ff
    private static final String DARK_DANGER = 'rgb(248, 81, 73)'   // --danger #f85149

    // CodeMirror's own default light syntax colors, which dark mode must leave untouched.
    private static final String LIGHT_STRING = 'rgb(170, 17, 17)'  // #a11
    private static final String LIGHT_NUMBER = 'rgb(17, 102, 68)'  // #164
    private static final String LIGHT_BOOL = 'rgb(34, 17, 153)'    // #219
    private static final String LIGHT_NULL = 'rgb(119, 0, 136)'    // #708

    def "defaults to system and follows the OS color scheme"() {
        when: "the OS prefers dark"
        emulate(ColorScheme.DARK)
        open()

        then: "no choice is forced and the page renders dark"
        themeAttr() == 'system'
        bodyBg() == DARK_BG

        when: "the OS switches to light"
        emulate(ColorScheme.LIGHT)

        then: "the page follows, still without a forced choice"
        themeAttr() == 'system'
        bodyBg() == LIGHT_BG
    }

    def "forcing dark overrides an OS light preference"() {
        given:
        emulate(ColorScheme.LIGHT)
        open()

        when:
        chooseTheme('Dark')

        then:
        themeAttr() == 'dark'
        bodyBg() == DARK_BG
        activeThemeLabel() == 'Dark'
    }

    def "forcing light overrides an OS dark preference"() {
        given:
        emulate(ColorScheme.DARK)
        open()

        when:
        chooseTheme('Light')

        then:
        themeAttr() == 'light'
        bodyBg() == LIGHT_BG
        activeThemeLabel() == 'Light'
    }

    def "persists the forced choice across a reload"() {
        given:
        emulate(ColorScheme.LIGHT)
        open()

        when:
        chooseTheme('Dark')
        page.reload()
        page.waitForSelector('.theme-toggle button.active')

        then: "the inline no-flash script restores the choice before paint"
        themeAttr() == 'dark'
        bodyBg() == DARK_BG
        activeThemeLabel() == 'Dark'
        page.evaluate("localStorage.getItem('apidocs-theme')") == 'dark'
    }

    def "switching back to system resumes following the OS"() {
        given:
        emulate(ColorScheme.DARK)
        open()
        chooseTheme('Light')

        when:
        chooseTheme('System')

        then: "the forced choice is dropped and the OS (dark) is followed again"
        themeAttr() == 'system'
        bodyBg() == DARK_BG
        activeThemeLabel() == 'System'
    }

    def "themes the body tabs and editor surfaces, and differentiates value types, in dark mode"() {
        given: "an operation with a request body, in dark mode"
        emulate(ColorScheme.DARK)
        open('POST-/widgets')
        openRawWith('{"s": "abc", "n": 42, "b": true, "z": null}')

        expect: "the now-inactive Form tab keeps readable text rather than the browser default"
        cssColor('.body-tabs button:not(.active)', 'color') == DARK_TEXT

        and: "the editor surfaces follow the theme"
        cssColor('.raw-body .cm-editor', 'backgroundColor') == DARK_BG
        cssColor('.raw-body .cm-gutters', 'backgroundColor') == DARK_BG_ALT

        and: "each JSON value type gets its own bright, distinct color"
        tokenColor('"abc"') == DARK_STRING
        tokenColor('42') == DARK_NUMBER
        tokenColor('true') == DARK_BOOL
        tokenColor('null') == DARK_NULL
    }

    def "leaves the original light syntax colors untouched"() {
        given:
        emulate(ColorScheme.LIGHT)
        open('POST-/widgets')
        openRawWith('{"s": "abc", "n": 42, "b": true, "z": null}')

        expect: "string / number / bool / null keep CodeMirror's defaults"
        tokenColor('"abc"') == LIGHT_STRING
        tokenColor('42') == LIGHT_NUMBER
        tokenColor('true') == LIGHT_BOOL
        tokenColor('null') == LIGHT_NULL
    }

    def "tints the lint error marker with the theme danger color in dark mode"() {
        given:
        emulate(ColorScheme.DARK)
        open('POST-/widgets')
        openRawWith('{ "oops": }')

        when: "the invalid JSON raises a gutter error marker"
        page.waitForSelector('.raw-body .cm-lint-marker-error')

        then:
        cssColor('.raw-body .cm-lint-marker-error', 'backgroundColor') == DARK_DANGER
    }

    // ---- helpers -------------------------------------------------------------

    /** Switches to the raw editor and replaces its content with {@code json}. */
    private void openRawWith(String json) {
        clickBodyTab('Raw JSON')
        page.waitForSelector('.raw-body .cm-gutters')
        rawFill(json)
    }

    private String cssColor(String selector, String property) {
        page.evaluate("s => getComputedStyle(document.querySelector(s)).${property}", selector) as String
    }

    /** Computed color of the first editor token whose text contains {@code text}. */
    private String tokenColor(String text) {
        Locator token = page.locator('.raw-body .cm-content span')
                .filter(new Locator.FilterOptions().setHasText(text)).first()
        token.evaluate('el => getComputedStyle(el).color') as String
    }

    private void emulate(ColorScheme scheme) {
        page.emulateMedia(new Page.EmulateMediaOptions().setColorScheme(scheme))
    }

    private void chooseTheme(String label) {
        page.locator(".theme-toggle button[aria-label='Theme: ${label}']").click()
    }

    private String themeAttr() {
        page.locator('html').getAttribute('data-theme')
    }

    private String activeThemeLabel() {
        page.locator('.theme-toggle button.active').getAttribute('aria-label') - 'Theme: '
    }

    private String bodyBg() {
        page.evaluate('getComputedStyle(document.body).backgroundColor') as String
    }
}
