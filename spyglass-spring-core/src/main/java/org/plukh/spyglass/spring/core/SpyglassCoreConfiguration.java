package org.plukh.spyglass.spring.core;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.info.BuildProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

/**
 * Stack-neutral Spring wiring for Spyglass, shared by the servlet and reactive adapters. It registers
 * the additive, vendor-neutral OpenAPI customizer (security scheme + default title + the generic
 * {@code x-service-name} info extension, and the build version filled into {@code info.version}).
 *
 * <p>This is imported by the web adapter's single entry point (e.g.
 * {@code org.plukh.spyglass.spring.webmvc.SpyglassConfiguration}); the adapter contributes the
 * stack-specific pieces (friendly redirects, asset serving) on top.
 */
@Configuration(proxyBeanMethods = false)
public class SpyglassCoreConfiguration {

    /**
     * The build version is filled into {@code info.version} (only when the consumer hasn't set a real one)
     * so the spec carries a meaningful version and the explorer's update check sees it change per build.
     * It comes from Spring Boot's {@link BuildProperties}, which exists only when the consumer's build runs
     * the {@code build-info} goal — absent that, this is a no-op and the spec keeps springdoc's default.
     * Best practice is still to set a real {@code info.version} that tracks the API contract; teams that
     * don't (e.g. microservices with no fixed API version) get the build version as a sensible fallback.
     * Disable with {@code apidocs.spec-version.from-build=false}.
     *
     * @param applicationName {@code spring.application.name}, or empty
     * @param versionFromBuild whether to fill {@code info.version} from the build version (default true)
     * @param buildProperties the build info, present only when the {@code build-info} goal ran
     *
     * @return the additive core customizer
     */
    @Bean
    public SpyglassOpenApiCustomizer spyglassOpenApiCustomizer(
            @Value("${spring.application.name:}") String applicationName,
            @Value("${apidocs.spec-version.from-build:true}") boolean versionFromBuild,
            ObjectProvider<BuildProperties> buildProperties) {
        String buildVersion = null;
        if (versionFromBuild) {
            BuildProperties props = buildProperties.getIfAvailable();
            buildVersion = props == null ? null : props.getVersion();
        }
        return new SpyglassOpenApiCustomizer(applicationName, buildVersion);
    }

    /**
     * Guard the adapters use to keep the explorer off a separate Actuator management port (see
     * {@link ManagementPortGuard} for why the leak happens and how the port is detected). Stack-neutral, so
     * both the servlet and reactive adapters share this one bean.
     *
     * @param environment the primary context environment (carries {@code local.server.port} and, when a
     *                     separate management port is configured, {@code local.management.port})
     *
     * @return the management-port guard
     */
    @Bean
    public ManagementPortGuard managementPortGuard(Environment environment) {
        return new ManagementPortGuard(environment);
    }
}
