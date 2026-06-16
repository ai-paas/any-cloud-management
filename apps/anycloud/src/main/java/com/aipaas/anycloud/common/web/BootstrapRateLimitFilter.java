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
 * Bootstrap anonymous endpoint per-IP rate limit.
 *
 * <p>대상: {@code /v1/agent-bootstrap/**} (CA bundle download — anonymous). Cache-Control:
 * max-age=300 이 90%+ 흡수하지만 cache miss / client-side cache 우회 / DDoS 시 보호 layer 필요.
 *
 * <p>구현: Caffeine 기반 fixed-window (1 분) per-IP counter. 한 분에 한 IP 가 limit 초과하면
 * HTTP 429 + {@code Retry-After: 60}. 단일 backend instance 내 in-memory — multi-replica 환경
 * 에서는 instance 별 limit (전체 효과 = replicas × limit). 정확한 cluster-wide rate limit 이
 * 필요하면 Redis / sticky session 도입.
 *
 * <p>Limit 결정 근거:
 * <ul>
 *   <li>정상 use case: cluster 등록 시 1회 / helm rollout 시 1회. 안정 시 IP 당 시간당 수회.</li>
 *   <li>60 req/min 은 reasonable headroom (실수 retry / 자동화 script 허용).</li>
 *   <li>Cache 5분 hit 가 대부분 흡수하므로 실제 backend hit 는 더 낮음.</li>
 * </ul>
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
