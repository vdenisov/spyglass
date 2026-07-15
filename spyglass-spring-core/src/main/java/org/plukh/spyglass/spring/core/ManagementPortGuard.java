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

    /** Sentinel for {@link #separateManagementPort}: the ports have not been resolved from the environment yet. */
    private static final int UNRESOLVED = -1;

    /**
     * Sentinel for {@link #separateManagementPort}: resolved, with no separate management server to guard.
     * {@code 0} is safe as "matches nothing" because a bound request always arrives on a real port (1–65535).
     */
    private static final int NO_SEPARATE_MANAGEMENT_PORT = 0;

    private final Environment environment;

    /**
     * The separate management port to decline the explorer on, resolved once and memoised:
     * {@link #NO_SEPARATE_MANAGEMENT_PORT} when there is none to guard against, {@link #UNRESOLVED} until first
     * resolved. The ports can't be read at construction time — Boot only publishes them once each web server
     * binds ({@code WebServerInitializedEvent}), after this bean is built — so they're resolved on the first
     * request (both servers are up by then) rather than re-read from the {@link Environment} on every one, this
     * guard being on the primary-port hot path via the adapters' global filter/interceptor. Benign race: any
     * two threads resolving concurrently compute the same value.
     */
    private volatile int separateManagementPort = UNRESOLVED;

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
        int managementPort = separateManagementPort;
        if (managementPort == UNRESOLVED) {
            managementPort = resolveSeparateManagementPort();
            separateManagementPort = managementPort;
        }
        return managementPort == requestLocalPort;
    }

    /**
     * Reads the two port properties once to decide whether a separate management server is in play.
     * {@link #NO_SEPARATE_MANAGEMENT_PORT} when no management port is configured, and for same-port Actuator,
     * where Boot aliases {@code local.management.port} to {@code local.server.port}.
     *
     * @return the separate management port, or {@link #NO_SEPARATE_MANAGEMENT_PORT} when there is none
     */
    private int resolveSeparateManagementPort() {
        @Nullable Integer managementPort = environment.getProperty(MANAGEMENT_PORT_PROPERTY, Integer.class);
        if (managementPort == null) {
            return NO_SEPARATE_MANAGEMENT_PORT;
        }
        @Nullable Integer serverPort = environment.getProperty(SERVER_PORT_PROPERTY, Integer.class);
        if (serverPort != null && managementPort.equals(serverPort)) {
            return NO_SEPARATE_MANAGEMENT_PORT;
        }
        return managementPort;
    }
}
