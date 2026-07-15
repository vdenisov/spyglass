package org.plukh.spyglass.spring.webmvc;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.plukh.spyglass.spring.core.ManagementPortGuard;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * Declines the explorer surfaces on a separate Actuator management port. Registered by
 * {@link SpyglassWebConfig} as a mapped interceptor over the explorer paths; because the host's
 * {@code WebMvcConfigurer}s are collected into the management child context, this interceptor is attached
 * to that context's mappings too — including the ones Boot's own default {@code /**} static handler would
 * otherwise serve — so a management-port request for an explorer path is short-circuited with a
 * {@code 404} regardless of which handler would have resolved it.
 *
 * <p>A bare {@code setStatus(404)} + {@code return false} is used (rather than {@code sendError}) to avoid
 * re-dispatching into the management context's error handling.
 */
final class ManagementPortInterceptor implements HandlerInterceptor {

    private final ManagementPortGuard guard;

    ManagementPortInterceptor(ManagementPortGuard guard) {
        this.guard = guard;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if (guard.isManagementPort(request.getLocalPort())) {
            response.setStatus(HttpServletResponse.SC_NOT_FOUND);
            return false;
        }
        return true;
    }
}
