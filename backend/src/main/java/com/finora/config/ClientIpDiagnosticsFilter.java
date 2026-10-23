package com.finora.config;

import com.finora.observability.ClientIpDiagnostics;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Feeds every request to {@link ClientIpDiagnostics}, which labels how the client IP was resolved
 * (no IPs are kept). Runs right after {@link RateLimitFilter}, so a request that limiter rejects is
 * not counted here; that does not bias the answer, since the question is about how IPs arrive, not
 * about which requests succeed. Switched off with {@code app.diagnostics.client-ip.enabled=false};
 * temporary, see {@link ClientIpDiagnostics}.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 2)
public class ClientIpDiagnosticsFilter extends OncePerRequestFilter {

    private final ClientIpDiagnostics diagnostics;
    private final boolean enabled;

    public ClientIpDiagnosticsFilter(ClientIpDiagnostics diagnostics,
                                     @Value("${app.diagnostics.client-ip.enabled:true}") boolean enabled) {
        this.diagnostics = diagnostics;
        this.enabled = enabled;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (enabled) {
            try {
                diagnostics.record(request);
            } catch (RuntimeException e) {
                // record() already swallows its own failures; this is the second line of defence
                // so a diagnostics bug can never fail a real request.
            }
        }
        chain.doFilter(request, response);
    }
}
