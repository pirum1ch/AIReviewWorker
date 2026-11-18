package com.review.worker.info;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.servlet.HandlerInterceptor;

import java.util.Locale;

/**
 * Enforces port-based routing isolation:
 * <ul>
 *   <li>{@code /worker/**} is only reachable on the info port (8082) — blocked on the actuator port.</li>
 *   <li>Everything else is only reachable on the actuator port (8081) — blocked on the info port.</li>
 * </ul>
 *
 * <p>URIs containing path-traversal or encoded-separator patterns are rejected regardless of port so
 * that no normalization ambiguity can bypass the routing check.
 */
class PortIsolationInterceptor implements HandlerInterceptor {

    private final int actuatorPort;
    private final int infoPort;

    PortIsolationInterceptor(int actuatorPort, int infoPort) {
        this.actuatorPort = actuatorPort;
        this.infoPort = infoPort;
    }

    @Override
    public boolean preHandle(HttpServletRequest req,
                             HttpServletResponse res,
                             Object handler) throws Exception {
        String uri = req.getRequestURI();
        if (isSuspiciousUri(uri)) {
            res.sendError(400);
            return false;
        }
        int port = req.getLocalPort();
        if (port == actuatorPort && uri.startsWith("/worker/")) {
            res.sendError(404);
            return false;
        }
        if (port == infoPort && !uri.startsWith("/worker/")) {
            res.sendError(404);
            return false;
        }
        return true;
    }

    /** Rejects URIs with path-traversal tokens or encoded separators. */
    private static boolean isSuspiciousUri(String uri) {
        String lower = uri.toLowerCase(Locale.ROOT);
        return lower.contains("..") || lower.contains("%2e") || lower.contains("%2f")
                || lower.contains("//") || lower.contains(";");
    }
}
