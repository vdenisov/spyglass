package org.plukh.spyglass.demo;

import org.plukh.spyglass.spring.webmvc.SpyglassConfiguration;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Import;

/**
 * Runnable, consumer-neutral showcase host for the explorer. Activating Spyglass is a single
 * {@code @Import(SpyglassConfiguration.class)}; springdoc generates {@code /v3/api-docs} from the
 * component-scanned {@link DemoController} (wired by {@link DemoEndpointsConfiguration} when
 * {@code apidocs.demo.enabled=true}, which this app sets), and the explorer renders it at
 * {@code /apidocs}.
 *
 * <p>Run with {@code mvn -pl spyglass-demo spring-boot:run}, then open
 * {@code http://localhost:8080/apidocs}.
 */
@SpringBootApplication
@Import(SpyglassConfiguration.class)
public class SpyglassDemoApplication {

    public static void main(String[] args) {
        SpringApplication.run(SpyglassDemoApplication.class, args);
    }
}
