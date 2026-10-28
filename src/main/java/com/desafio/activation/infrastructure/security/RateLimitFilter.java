package com.desafio.activation.infrastructure.security;

import com.desafio.activation.infrastructure.web.CorrelationIdFilter;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import io.github.bucket4j.Bucket;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Duration;
import java.util.concurrent.TimeUnit;

/**
 * Token bucket per authenticated client (claim azp, else sub). Runs after JWT
 * authentication; anonymous requests pass through and are rejected by the
 * authorization rules. Buckets live in memory, so the limit applies per instance.
 */
class RateLimitFilter extends OncePerRequestFilter {

    private final Cache<String, Bucket> buckets;
    private final RateLimitProperties properties;

    RateLimitFilter(RateLimitProperties properties) {
        this.properties = properties;
        this.buckets = Caffeine.newBuilder()
                .expireAfterAccess(Duration.ofMinutes(10))
                .maximumSize(100_000)
                .build();
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (!(SecurityContextHolder.getContext().getAuthentication() instanceof JwtAuthenticationToken token)) {
            chain.doFilter(request, response);
            return;
        }
        String authorizedParty = token.getToken().getClaimAsString("azp");
        String clientId = authorizedParty != null ? authorizedParty : token.getToken().getSubject();
        var probe = buckets.get(clientId, key -> newBucket()).tryConsumeAndReturnRemaining(1);
        if (probe.isConsumed()) {
            chain.doFilter(request, response);
            return;
        }
        long retryAfterSeconds = Math.max(1, TimeUnit.NANOSECONDS.toSeconds(probe.getNanosToWaitForRefill()) + 1);
        response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
        response.setHeader(HttpHeaders.RETRY_AFTER, Long.toString(retryAfterSeconds));
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        response.getWriter().write("""
                {"type":"/problems/too-many-requests","title":"Too Many Requests","status":429,\
                "detail":"Rate limit exceeded, retry later","correlationId":"%s"}"""
                .formatted(MDC.get(CorrelationIdFilter.MDC_KEY)));
    }

    private Bucket newBucket() {
        return Bucket.builder()
                .addLimit(limit -> limit.capacity(properties.burst())
                        .refillGreedy(properties.requestsPerMinute(), Duration.ofMinutes(1)))
                .build();
    }
}
