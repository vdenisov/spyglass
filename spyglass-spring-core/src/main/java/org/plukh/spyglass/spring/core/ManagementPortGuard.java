package org.plukh.spyglass.spring.core;

import org.jspecify.annotations.Nullable;
import org.springframework.core.env.Environment;

/**
 * Answers whether a request arrived on a <em>separate</em> Actuator management port, so the adapters can
 * decline to serve the explorer there.
 *
 * <p>When a host sets {@code management.server.port} to a value different from {@code server.port}, Boot
 * stands up the management endpoints in a child application context. That child's web infrastructure
 * collects beans <em>including ancestors</em>, so the explorer's redirects and asset handlers — and Boot's
 * own default {@code /**} static-resource handler — are applied to the management port too, even though the
 * explorer can't function there (its spec is not on that port). The adapters use this guard to {@code 404}
 * the explorer surfaces on the management port while leaving the primary port untouched.
 *
 * <p><strong>How the port is known.</strong> Boot's {@code ServerPortInfoApplicationContextInitializer}
 * publishes {@code local.server.port} and {@code local.management.port} on {@code WebServerInitializedEvent}
 * and propagates them up to the primary context, so a guard bean in the primary context can answer for
 * requests arriving on either port. Reading these stable, version-neutral properties (the same convention
 * the tests use for {@code local.server.port}) avoids any Boot-version-specific type — the
 * {@code WebServerApplicationContext} type moved packages between Boot 3 and Boot 4, so it is deliberately
 * not referenced here.
 */
public final class ManagementPortGuard {

    private static final String MANAGEMENT_PORT_PROPERTY = "local.management.port";
    private static final String SERVER_PORT_PROPERTY = "local.server.port";

    private final Environment environment;

    public ManagementPortGuard(Environment environment) {
        this.environment = environment;
    }

    /**
     * Whether {@code requestLocalPort} is a separate management port. False when no management port is
     * configured, and false for same-port Actuator ({@code management.server.port == server.port}), where
     * Boot aliases {@code local.management.port} to {@code local.server.port} — in both cases the explorer
     * is served normally.
     *
     * @param requestLocalPort the local port the request arrived on
     *
     * @return {@code true} only when a separate management server is configured and the request hit it
     */
    public boolean isManagementPort(int requestLocalPort) {
        @Nullable Integer managementPort = environment.getProperty(MANAGEMENT_PORT_PROPERTY, Integer.class);
        if (managementPort == null) {
            return false;
        }
        @Nullable Integer serverPort = environment.getProperty(SERVER_PORT_PROPERTY, Integer.class);
        if (serverPort != null && managementPort.equals(serverPort)) {
            return false;
        }
        return managementPort == requestLocalPort;
    }
}
