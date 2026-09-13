package com.paytm.wallettransfer.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Map;
import java.util.Set;

/**
 * Simple bearer-token auth filter.
 *
 * Token format: a positive integer string.
 * The token IS the userId — no DB table required.
 *
 * Example: Authorization: Bearer 42  →  authenticatedUserId = 42
 *
 * Open paths (no token required): /actuator, /metrics, /logs, /error.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 2)   // after CorrelationIdFilter(HP) and MetricsFilter(HP+1)
public class AuthFilter extends OncePerRequestFilter {

    /** Request attribute key where the resolved userId is stored. */
    public static final String USER_ID_ATTR = "authenticatedUserId";

    private static final Set<String> OPEN_PREFIXES = Set.of(
            "/actuator", "/metrics", "/logs", "/error"
    );

    private final ObjectMapper objectMapper;

    public AuthFilter(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain)
            throws ServletException, IOException {

        String path = request.getRequestURI();
        if (OPEN_PREFIXES.stream().anyMatch(path::startsWith)) {
            filterChain.doFilter(request, response);
            return;
        }

        String authHeader = request.getHeader("Authorization");
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            sendUnauthorized(response, "Missing or invalid Authorization header. Expected: Authorization: Bearer <userId>");
            return;
        }

        String token = authHeader.substring(7).trim();
        try {
            int userId = Integer.parseInt(token);
            if (userId <= 0) {
                sendUnauthorized(response, "Bearer token must be a positive integer userId");
                return;
            }
            request.setAttribute(USER_ID_ATTR, userId);
            filterChain.doFilter(request, response);
        } catch (NumberFormatException e) {
            sendUnauthorized(response, "Bearer token must be a positive integer userId");
        }
    }

    private void sendUnauthorized(HttpServletResponse response, String message) throws IOException {
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        objectMapper.writeValue(response.getWriter(), Map.of("error", message));
    }
}
