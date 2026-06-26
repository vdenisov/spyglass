package org.plukh.spyglass.demo;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.Builder;
import lombok.Value;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A second demo controller, in its own {@link Tag} ("Health checks"), so the explorer showcases more
 * than one operation group: the sidebar renders a tag section per controller, and a live filter
 * furls those tag groups into relevance-ranked, matched-field sections (clearing the filter restores
 * them). Both summaries mention "probe", so filtering on it spans this group and the main demo's wide
 * form — a quick way to see cross-group ranking.
 *
 * <p>Registered the same way as {@link DemoController}: component-scanned by the demo app and also
 * contributed as a gated {@code @Bean} (see {@link DemoEndpointsConfiguration}) for embedded consumers
 * that don't scan this package.
 */
@RestController
@RequestMapping(value = "/apidocs-demo/health", produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "Health checks", description = "A second endpoint group, so the explorer shows multiple tag sections.")
public class HealthController {

    @Operation(
            summary = "Liveness probe",
            description = "Demo — a minimal GET in a second tag group; reports that the service is up.")
    @GetMapping("/live")
    public HealthStatus liveness() {
        return HealthStatus.builder().status("UP").build();
    }

    @Operation(
            summary = "Readiness probe (dependency checks)",
            description = "Demo — reports readiness with a per-dependency status map, so the response is a "
                    + "little richer than the liveness probe.")
    @GetMapping("/ready")
    public HealthStatus readiness() {
        Map<String, String> checks = new LinkedHashMap<>();
        checks.put("database", "UP");
        checks.put("cache", "UP");
        return HealthStatus.builder().status("UP").checks(checks).build();
    }

    @Value
    @Builder
    @Schema(description = "A health probe result.")
    public static class HealthStatus {
        @Schema(description = "Overall status.", example = "UP")
        String status;
        @Schema(description = "Per-dependency status; present on the readiness probe.")
        Map<String, String> checks;
    }
}
