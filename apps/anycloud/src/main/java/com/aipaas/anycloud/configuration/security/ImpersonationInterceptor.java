package com.aipaas.anycloud.configuration.security;

import io.aipaas.cluster.agent.identity.ImpersonationIdentity;
import io.aipaas.cluster.agent.identity.ThreadLocalImpersonationContext;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * gateway 가 검증한 사용자 identity 를 trusted header 에서 읽어 K8s Impersonation 으로 넘깁니다.
 * 같은 thread 의 후속 starter K8s 호출이 그 사용자의 RBAC 로 평가됩니다.
 *
 * <p>{@code afterCompletion} 에서 반드시 clear 합니다. ThreadLocal 이라 빠뜨리면 다음 요청이 앞
 * 사용자의 권한으로 돕니다.
 *
 * <p>{@code security.auth.enabled=true} 일 때만 {@link WebMvcImpersonationConfig} 가 등록합니다.
 * 꺼져 있거나 interceptor 가 닿지 않는 비동기 경로(RabbitMQ listener, scheduled job)에서는 holder 가
 * 비어 starter 가 admin 권한으로 호출합니다. system action 은 그게 맞습니다.
 */
@Slf4j
public class ImpersonationInterceptor implements HandlerInterceptor {

    /** 게이트웨이가 검증한 user identity header. 표준 trusted-proxy 컨벤션. */
    public static final String USER_HEADER = "X-Forwarded-User";
    /** Group memberships: CSV 또는 multi-value. */
    public static final String GROUPS_HEADER = "X-Forwarded-Groups";
    /** Extras prefix: header name 의 lowercased suffix 가 K8s Impersonate-Extra-<key>. */
    public static final String EXTRA_HEADER_PREFIX = "X-Forwarded-Extra-";

    public ImpersonationInterceptor() {}

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        String user = trim(request.getHeader(USER_HEADER));
        if (user == null || user.isEmpty()) {
            return true; // 헤더 없으면 admin-equivalent 동작 — 별도 처리 없음.
        }
        try {
            List<String> groups = parseGroups(request);
            Map<String, List<String>> extras = parseExtras(request);
            ImpersonationIdentity identity = new ImpersonationIdentity(user, groups, extras);
            ThreadLocalImpersonationContext.set(identity);
            log.debug(
                    "ImpersonationInterceptor: set user={} groups={} method={} uri={}",
                    user,
                    groups,
                    request.getMethod(),
                    request.getRequestURI());
        } catch (RuntimeException e) {
            // identity 생성 실패는 fail-open: log + admin-equivalent 로 진행. fail-closed 가 필요한
            // 환경 (compliance) 에서는 본 catch 를 throw 로 변경 + 4xx 응답.
            log.warn("ImpersonationInterceptor: identity build failed user={}: {}", user, e.toString());
        }
        return true;
    }

    @Override
    public void afterCompletion(
            HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) {
        // ThreadLocal cleanup: leak 시 같은 thread 의 다음 request 가 잘못된 identity 사용.
        ThreadLocalImpersonationContext.clear();
    }

    // ---- helpers ----

    /** CSV 또는 multi-value header 의 group 목록 파싱. */
    private static List<String> parseGroups(HttpServletRequest request) {
        List<String> out = new ArrayList<>();
        java.util.Enumeration<String> values = request.getHeaders(GROUPS_HEADER);
        while (values != null && values.hasMoreElements()) {
            String v = values.nextElement();
            if (v == null) continue;
            // CSV 분해: gateway 가 multi-value 대신 CSV 로 단일 header 전송하는 케이스 cover.
            for (String token : v.split(",")) {
                String t = trim(token);
                if (t != null && !t.isEmpty()) out.add(t);
            }
        }
        return Collections.unmodifiableList(out);
    }

    /** X-Forwarded-Extra-<key>: <value> 헤더 enumeration → map. */
    private static Map<String, List<String>> parseExtras(HttpServletRequest request) {
        Map<String, List<String>> out = new LinkedHashMap<>();
        java.util.Enumeration<String> headerNames = request.getHeaderNames();
        while (headerNames != null && headerNames.hasMoreElements()) {
            String name = headerNames.nextElement();
            if (name == null) continue;
            String lower = name.toLowerCase(Locale.ROOT);
            String prefix = EXTRA_HEADER_PREFIX.toLowerCase(Locale.ROOT);
            if (!lower.startsWith(prefix) || lower.length() == prefix.length()) {
                continue;
            }
            String key = lower.substring(prefix.length());
            List<String> values = new ArrayList<>();
            java.util.Enumeration<String> headerValues = request.getHeaders(name);
            while (headerValues != null && headerValues.hasMoreElements()) {
                String v = headerValues.nextElement();
                if (v != null) values.addAll(Arrays.asList(v.split(",")));
            }
            // trim + dedupe-preserving order.
            List<String> normalized = new ArrayList<>();
            for (String v : values) {
                String t = trim(v);
                if (t != null && !t.isEmpty()) normalized.add(t);
            }
            if (!normalized.isEmpty()) {
                out.put(key, normalized);
            }
        }
        return Collections.unmodifiableMap(out);
    }

    private static String trim(String s) {
        return s == null ? null : s.trim();
    }
}
