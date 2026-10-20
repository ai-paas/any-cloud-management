package com.aipaas.anycloud.configuration.security;

import java.util.ArrayList;
import java.util.List;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 백엔드 자체 정적 토큰 인증 설정. 운영에서는 gateway 가 인증을 담당하므로 기본값 비활성.
 * gateway 우회 호출 차단이나 staging 디버깅 시 활성화.
 *
 * <p>{@code scheme} 은 {@code header} 가 {@code Authorization} 인 경우에만 적용. 미지정 시
 * 헤더 값을 raw 토큰으로 해석.
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
