package com.aipaas.anycloud.common.web;

import com.aipaas.anycloud.domain.agent.IdempotencyRecordEntity;
import com.aipaas.anycloud.domain.agent.IdempotencyRecordRepository;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.ContentCachingResponseWrapper;

/**
 * Idempotency-Key 헤더 처리 (Stripe 패턴).
 * <ul>
 * <li>POST + Idempotency-Key 헤더 → 24h 동안 같은 key 가 다시 오면 첫 응답을 재현</li>
 * <li>같은 key 인데 body/path 가 다르면 409 Conflict</li>
 * <li>GET / 멱등 method 는 무시 (오버헤드 없음)</li>
 * </ul>
 */
// anycloud.idempotency.enabled=false 로 비활성 가능 (slice 테스트 / 운영 측 개별 비활성).
@Slf4j
@Component
@RequiredArgsConstructor
@Order(Ordered.HIGHEST_PRECEDENCE + 50) // RequestMdcFilter 다음
@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(
        prefix = "anycloud.idempotency",
        name = "enabled",
        havingValue = "true",
        matchIfMissing = true)
public class IdempotencyFilter extends OncePerRequestFilter {

    public static final String IDEMPOTENCY_HEADER = "Idempotency-Key";
    /**
     * Replay 응답 식별 헤더 — 클라이언트가 첫 호출 vs 캐시 replay 구분 가능.
     * "true" / 없음.
     */
    public static final String IDEMPOTENCY_REPLAY_HEADER = "X-Idempotency-Replay";
    /**
     * 캐시된 응답 body 가 cap (1MB) 초과로 잘렸음을 표시. body 가 비어 보일 때 클라이언트가
     * 빈 응답을 오류로 오인하지 않도록 함. 동반된 Location 헤더 (있다면) 로 후속 조회 가능.
     */
    public static final String IDEMPOTENCY_BODY_TRUNCATED_HEADER = "X-Idempotency-Body-Truncated";

    private static final Set<String> APPLIES_TO = Set.of("POST", "PATCH");
    private static final java.time.Duration TTL = java.time.Duration.ofHours(24);
    /**
     * Response body cache 상한 (1 MB). 초과 응답은 status code 와 fingerprint 만 캐시되어 같은 key
     * 재시도 시 빠른 short-circuit 으로 fall-through (replay body 없이 dedup 효과만 유지). chart
     * deploy 같은 대용량 응답이 DB 를 부풀리거나 replay 메모리 spike 를 일으키지 않.
     */
    private static final int MAX_CACHED_BODY_BYTES = 1_000_000;

    private final IdempotencyRecordRepository repository;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String key = request.getHeader(IDEMPOTENCY_HEADER);
        if (key == null || key.isBlank() || !APPLIES_TO.contains(request.getMethod())) {
            chain.doFilter(request, response);
            return;
        }
        // Body 를 byte[] 로 1회 read 후 자체 wrapper 의 getInputStream() 이 매번 새 stream 으로 재생
        CachedBodyHttpServletRequest wrapped = new CachedBodyHttpServletRequest(request);
        byte[] bodyBytes = wrapped.getCachedBody();

        // 첫 호출: cached 가 있으면 replay.
        var cached = repository.findById(key).orElse(null);
        if (cached != null) {
            if (cached.getExpiresAt() != null && cached.getExpiresAt().isBefore(LocalDateTime.now())) {
                repository.deleteById(key);
            } else {
                String fp = fingerprint(request.getMethod(), request.getRequestURI(), bodyBytes);
                if (!cached.getRequestFingerprint().equals(fp)) {
                    log.warn(
                            "Idempotency-Key conflict: key={}, method={}, path={}",
                            key,
                            request.getMethod(),
                            request.getRequestURI());
                    response.setStatus(HttpServletResponse.SC_CONFLICT);
                    response.setContentType("application/json");
                    response.getWriter()
                            .write(
                                    "{\"success\":false,\"status\":409,\"message\":\"Idempotency-Key conflict — body or path differs from original request.\"}");
                    return;
                }
                boolean bodyTruncated = cached.getResponseBody() == null;
                log.info(
                        "Idempotency-Key replay: key={}, status={}, bodyTruncated={}",
                        key,
                        cached.getStatusCode(),
                        bodyTruncated);
                response.setStatus(cached.getStatusCode());
                response.setContentType("application/json");
                response.setHeader(IDEMPOTENCY_REPLAY_HEADER, "true");
                if (bodyTruncated) {
                    // 빈 body 가 의도된 결과임을 명시 — 클라이언트는 빈 응답을 4xx 로 오인 X.
                    response.setHeader(IDEMPOTENCY_BODY_TRUNCATED_HEADER, "true");
                }
                response.getWriter().write(bodyTruncated ? "" : cached.getResponseBody());
                return;
            }
        }

        // 새 요청: 응답 캡처 후 저장. Spring 의 ContentCachingResponseWrapper 가 OutputStream / Writer
        // 모두 정확히 capture — replay 시 body 보존 보장.
        ContentCachingResponseWrapper cap = new ContentCachingResponseWrapper(response);
        chain.doFilter(wrapped, cap); // wrapped.getInputStream() 이 controller 에서 재read 가능

        // 2xx 응답만 캐싱 (5xx 재시도가 매번 같은 5xx 를 받지 않도록).
        int statusCode = cap.getStatus();
        if (statusCode >= 200 && statusCode < 300) {
            try {
                String body = new String(cap.getContentAsByteArray(), StandardCharsets.UTF_8);
                // 1MB 초과 body 는 메타데이터만 캐시. 같은 key 재시도 시 200 으로 short-circuit
                // 하지만 body 는 비어 있게 됨 — caller 는 응답 본문이 필요하면 별도 GET 호출로 재조회.
                if (body != null && body.length() > MAX_CACHED_BODY_BYTES) {
                    log.warn(
                            "Idempotency body exceeds {} bytes (key={}, size={}). "
                                    + "Caching metadata only — replay returns empty body.",
                            MAX_CACHED_BODY_BYTES,
                            key,
                            body.length());
                    body = null;
                }
                IdempotencyRecordEntity rec = IdempotencyRecordEntity.builder()
                        .idempotencyKey(key)
                        .requestFingerprint(fingerprint(request.getMethod(), request.getRequestURI(), bodyBytes))
                        .statusCode(statusCode)
                        .responseBody(body)
                        .createdAt(LocalDateTime.now())
                        .expiresAt(LocalDateTime.now().plus(TTL))
                        .build();
                repository.save(rec);
            } catch (Exception e) {
                // 캐시 실패는 비즈니스에 영향 X. 로그만.
                log.warn("Idempotency cache save failed: key={}, err={}", key, e.toString());
            }
        }

        // ★ critical — ContentCachingResponseWrapper 는 buffer 만 — copyBodyToResponse() 호출해야
        // 실제 client 에 전송됨. 누락하면 client 가 빈 응답 받음.
        cap.copyBodyToResponse();
    }

    private static String fingerprint(String method, String uri, byte[] body) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            md.update(method.getBytes(StandardCharsets.UTF_8));
            md.update((byte) 0);
            md.update(uri.getBytes(StandardCharsets.UTF_8));
            md.update((byte) 0);
            if (body != null) md.update(body);
            return HexFormat.of().formatHex(md.digest()).substring(0, 32);
        } catch (Exception e) {
            return "";
        }
    }

    /**
     * Request body 를 byte[] 로 미리 cache 해두고, getInputStream() 호출마다 새
     * ByteArrayInputStream
     * 을 반환. Spring 의 ContentCachingRequestWrapper 와 달리 stream 재read 안전.
     */
    private static final class CachedBodyHttpServletRequest extends HttpServletRequestWrapper {
        private final byte[] cachedBody;

        CachedBodyHttpServletRequest(HttpServletRequest request) throws IOException {
            super(request);
            this.cachedBody = request.getInputStream().readAllBytes();
        }

        byte[] getCachedBody() {
            return cachedBody;
        }

        @Override
        public ServletInputStream getInputStream() {
            final ByteArrayInputStream bais = new ByteArrayInputStream(cachedBody);
            return new ServletInputStream() {
                @Override
                public boolean isFinished() {
                    return bais.available() == 0;
                }

                @Override
                public boolean isReady() {
                    return true;
                }

                @Override
                public void setReadListener(ReadListener listener) {
                    /* sync mode */ }

                @Override
                public int read() {
                    return bais.read();
                }

                @Override
                public int read(byte[] b, int off, int len) {
                    return bais.read(b, off, len);
                }
            };
        }

        @Override
        public BufferedReader getReader() {
            return new BufferedReader(new InputStreamReader(getInputStream(), StandardCharsets.UTF_8));
        }
    }

    // CapturingResponseWrapper inner class 제거 (ContentCachingResponseWrapper 위임).
}
