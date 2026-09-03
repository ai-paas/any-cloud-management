package com.aipaas.anycloud.common.web;

import com.aipaas.anycloud.common.logging.LoggingMdc;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * 모든 HTTP 요청에 대해 짧은 requestId 와 clientIp 를 MDC 에 채워, controller / service /
 * 호출하는 외부 시스템까지의 모든 로그 줄에 동일한 컨텍스트가 표시되.
 * <p>
 * X-Request-Id 헤더가 들어오면 그 값을 우선 사용(분산 추적과 연동). 없으면 UUID 앞 8자.
 * 응답에도 X-Request-Id 헤더를 넣어 클라이언트가 동일 id 로 다시 추적 가능.
 * <p>
 * X-Forwarded-For 가 있으면 첫 hop 을 clientIp 로 사용(gateway 뒤에 있는 환경 고려).
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class RequestMdcFilter extends OncePerRequestFilter {

    public static final String REQUEST_ID_HEADER = "X-Request-Id";
    private static final String FORWARDED_FOR_HEADER = "X-Forwarded-For";
    private static final int REQUEST_ID_LEN = 8;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String requestId = resolveRequestId(request);
        String clientIp = resolveClientIp(request);
        // 응답 envelope 의 processingTimeMs 계산용. ResponseEnvelopeAdvice 에서 사용.
        request.setAttribute(ResponseEnvelopeAdvice.REQUEST_START_NS_ATTR, System.nanoTime());

        try (var ignored = LoggingMdc.scope(java.util.Map.of(
                LoggingMdc.REQUEST_ID, requestId,
                LoggingMdc.CLIENT_IP, clientIp))) {
            response.setHeader(REQUEST_ID_HEADER, requestId);
            chain.doFilter(request, response);
        }
    }

    private String resolveRequestId(HttpServletRequest request) {
        String incoming = request.getHeader(REQUEST_ID_HEADER);
        if (incoming != null && !incoming.isBlank()) {
            // 외부에서 들어온 값을 그대로 신뢰하되 길이만 잘라 잡음 방지.
            return incoming.length() > 32 ? incoming.substring(0, 32) : incoming;
        }
        String uuid = UUID.randomUUID().toString().replace("-", "");
        return uuid.substring(0, REQUEST_ID_LEN);
    }

    private String resolveClientIp(HttpServletRequest request) {
        String forwarded = request.getHeader(FORWARDED_FOR_HEADER);
        if (forwarded != null && !forwarded.isBlank()) {
            int comma = forwarded.indexOf(',');
            String first = comma < 0 ? forwarded : forwarded.substring(0, comma);
            return first.trim();
        }
        return request.getRemoteAddr();
    }
}
