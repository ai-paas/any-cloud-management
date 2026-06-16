package com.aipaas.anycloud.configuration.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.util.AntPathMatcher;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * 사전 공유 정적 토큰 비교 필터.
 * <p>
 * 요청 헤더 {@code security.auth.header}(기본 {@code Authorization})에서 토큰을 추출하여
 * 설정값과 {@link MessageDigest#isEqual(byte[], byte[]) 상수 시간 비교}로 일치를 검사한다.
 * 공개 경로({@code security.auth.public-paths})는 검사하지 않는다.
 */
@Slf4j
public class StaticTokenAuthFilter extends OncePerRequestFilter {

    private static final AntPathMatcher PATH_MATCHER = new AntPathMatcher();
    private static final String INTERNAL_PRINCIPAL = "gateway";
    private static final List<SimpleGrantedAuthority> INTERNAL_AUTHORITIES =
            List.of(new SimpleGrantedAuthority("ROLE_INTERNAL"));

    private final SecurityProperties properties;
    private final byte[] expectedTokenBytes;

    public StaticTokenAuthFilter(SecurityProperties properties) {
        this.properties = properties;
        this.expectedTokenBytes = properties.getToken().getBytes(StandardCharsets.UTF_8);
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        for (String pattern : properties.getPublicPaths()) {
            if (PATH_MATCHER.match(pattern, path)) {
                return true;
            }
        }
        return false;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {

        String headerValue = request.getHeader(properties.getHeader());
        String providedToken = extractToken(headerValue);

        if (providedToken == null) {
            deny(response, "missing_token", "Missing or malformed authentication header");
            return;
        }

        byte[] providedBytes = providedToken.getBytes(StandardCharsets.UTF_8);
        if (!MessageDigest.isEqual(expectedTokenBytes, providedBytes)) {
            log.warn(
                    "Static token mismatch on {} {} from {}",
                    request.getMethod(),
                    request.getRequestURI(),
                    request.getRemoteAddr());
            deny(response, "invalid_token", "Invalid authentication token");
            return;
        }

        AbstractAuthenticationToken authentication = new InternalAuthentication();
        SecurityContextHolder.getContext().setAuthentication(authentication);
        chain.doFilter(request, response);
    }

    private String extractToken(String headerValue) {
        if (headerValue == null || headerValue.isBlank()) {
            return null;
        }
        String scheme = properties.getScheme();
        if (scheme == null || scheme.isBlank()) {
            return headerValue.trim();
        }
        String prefix = scheme + " ";
        if (!headerValue.regionMatches(true, 0, prefix, 0, prefix.length())) {
            return null;
        }
        String token = headerValue.substring(prefix.length()).trim();
        return token.isEmpty() ? null : token;
    }

    private void deny(HttpServletResponse response, String code, String message) throws IOException {
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter()
                .write("{\"code\":\"" + code + "\",\"status\":401,\"message\":\"" + message + "\",\"errors\":[]}");
    }

    /** Spring Security 인증 마커 — 권한은 ROLE_INTERNAL 고정. */
    private static final class InternalAuthentication extends AbstractAuthenticationToken {
        private static final long serialVersionUID = 1L;

        InternalAuthentication() {
            super(INTERNAL_AUTHORITIES);
            setAuthenticated(true);
        }

        @Override
        public Object getCredentials() {
            return "";
        }

        @Override
        public Object getPrincipal() {
            return INTERNAL_PRINCIPAL;
        }
    }
}
