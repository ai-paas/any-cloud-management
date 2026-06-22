package com.aipaas.anycloud.configuration.security;

import java.util.ArrayList;
import java.util.List;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 백엔드 자체 정적 토큰 인증 설정.
 * <p>
 * 운영 환경에서는 외부 gateway가 인증을 담당하므로 기본값은 OFF다.
 * gateway 우회 호출을 차단하거나 staging/디버깅 시에 ON으로 켤 수 있도록 toggle 형태로 제공.
 *
 * <pre>
 * security:
 *   auth:
 *     enabled: false                # 기본 OFF
 *     header: Authorization          # 또는 X-Internal-Token
 *     scheme: Bearer                  # 헤더가 Authorization 일 때만 사용. 빈 값이면 raw 토큰
 *     token: ${SECURITY_AUTH_TOKEN:}  # 사전 공유 시크릿
 *     public-paths:                   # 인증 우회 경로
 *       - /actuator/health
 *       - /docs/**
 * </pre>
 */
@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "security.auth")
public class SecurityProperties {

    /** 인증 활성화 여부 (기본 OFF, gateway 인증 위임 전제). */
    private boolean enabled = false;

    /** 검사 대상 헤더. */
    private String header = "Authorization";

    /** Authorization 헤더용 prefix scheme (예: "Bearer"). 빈 값이면 헤더 값과 token을 그대로 비교. */
    private String scheme = "Bearer";

    /** 사전 공유 시크릿. enabled=true일 때 비어 있으면 부팅 실패시킴. */
    private String token = "";

    /** 인증을 건너뛸 경로 (Spring AntPathMatcher 패턴). */
    private List<String> publicPaths = new ArrayList<>(List.of(
            "/actuator/health",
            "/actuator/info",
            "/actuator/prometheus",
            "/docs/**",
            "/docs",
            "/swagger-ui/**",
            "/swagger-ui.html",
            "/v3/api-docs/**"));
}
