package org.plukh.spyglass.test

/**
 * Shared OpenAPI spec fixtures for the explorer browser specs. A single source for the small
 * one-operation document the update-check specs mock, so its JSON shape lives in one place rather than
 * being copy-pasted across the webmvc/webflux specs and the ETag test app.
 */
final class SpecFixtures {

    private SpecFixtures() {
    }

    /**
     * A minimal valid one-operation spec whose only varying part is {@code info.version}, so each distinct
     * version string hashes distinctly — exactly what the update check keys on.
     *
     * @param version the value to place in {@code info.version}
     *
     * @return the spec as a JSON string
     */
    static String specWithVersion(String version) {
        '{"openapi":"3.1.0","info":{"title":"Fixture Explorer API","version":"' + version +
                '"},"paths":{"/widgets/{id}":{"get":{"summary":"Get a widget","responses":{"200":{"description":"ok"}}}}}}'
    }
}
