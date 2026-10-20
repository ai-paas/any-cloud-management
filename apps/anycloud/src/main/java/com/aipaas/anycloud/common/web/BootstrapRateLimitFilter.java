package com.aipaas.anycloud.common.web;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * {@code /v1/agent-bootstrap/**} 의 IP 별 분당 호출을 제한합니다. 인증 없이 열려 있는 CA bundle
 * 다운로드라 cache 를 우회하는 호출을 막을 층이 필요합니다. 초과하면 429 와 {@code Retry-After: 60}
 * 을 돌려줍니다.
 *
 * <p>카운터가 instance 메모리에 있어 실제 상한은 replica 수를 곱한 값입니다. 클러스터 전체에
 * 정확한 상한을 걸려면 Redis 가 필요합니다.
 */
@Slf4j
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 60) // IdempotencyFilter 보다 후순위
public class BootstrapRateLimitFilter extends OncePerRequestFilter {

    /** 대상 URL prefix — agent bootstrap anonymous endpoint. */
    private static final String PROTECTED_PREFIX = "/v1/agent-bootstrap/";

    /** Per-IP fixed window. */
    private static final Duration WINDOW = Duration.ofMinutes(1);

    private static final int MAX_REQUESTS_PER_WINDOW = 60;

    /**
     * IP → 1분 window 내 누적 카운트. Caffeine expireAfterWrite 가 자동으로 window 만료.
     * maximumSize 로 hostile scanner 의 무한 IP 변종도 bounded.
     */
    private final Cache<String, AtomicInteger> counts =
            Caffeine.newBuilder().expireAfterWrite(WINDOW).maximumSize(10_000).build();

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String path = request.getRequestURI();
        if (!path.startsWith(PROTECTED_PREFIX)) {
            chain.doFilter(request, response);
            return;
        }

        String clientIp = extractClientIp(request);
        AtomicInteger counter = counts.get(clientIp, k -> new AtomicInteger(0));
        int current = counter.incrementAndGet();

        if (current > MAX_REQUESTS_PER_WINDOW) {
            log.warn(
                    "Bootstrap rate limit exceeded ip={} path={} count={}/{} per {}s",
                    clientIp,
                    path,
                    current,
                    MAX_REQUESTS_PER_WINDOW,
                    WINDOW.toSeconds());
            response.setStatus(429); // HTTP 429 Too Many Requests (Servlet API 에 상수 없음)
            response.setHeader("Retry-After", String.valueOf(WINDOW.toSeconds()));
            response.setContentType("application/json");
            response.getWriter()
                    .write("{\"success\":false,\"status\":429,\"code\":\"RATE_LIMITED\","
                            + "\"message\":\"Too many requests to bootstrap endpoint. "
                            + "Retry after " + WINDOW.toSeconds() + "s.\"}");
            return;
        }

        chain.doFilter(request, response);
    }

    /**
     * X-Forwarded-For 우선 (gateway/LB 통과 시 client IP 보존), 없으면 remote addr.
     * 첫 번째 IP 만 사용 (left-most = original client).
     */
    private static String extractClientIp(HttpServletRequest request) {
        String xff = request.getHeader("X-Forwarded-For");
        if (xff != null && !xff.isBlank()) {
            int comma = xff.indexOf(',');
            return (comma > 0 ? xff.substring(0, comma) : xff).trim();
        }
        String realIp = request.getHeader("X-Real-IP");
        if (realIp != null && !realIp.isBlank()) {
            return realIp.trim();
        }
        return request.getRemoteAddr();
    }
}
