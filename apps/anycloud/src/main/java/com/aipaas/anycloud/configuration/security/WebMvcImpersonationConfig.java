package com.aipaas.anycloud.configuration.security;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * {@link ImpersonationInterceptor} 등록 — {@code security.auth.enabled=true} 일 때만 활성.
 *
 * <p>인증 toggle OFF 시 등록 안 됨 → ThreadLocalImpersonationContext 가 빈 채 유지 → starter 가
 * admin-equivalent 호출 (현재 동작 보존).
 *
 * <p>적용 path: 모든 controller 경로 ({@code /**}). actuator / health endpoint 도 포함되나 거기서
 * agent gRPC 호출이 일어나지 않으므로 영향 없음. 필요시 {@code excludePathPatterns} 로 narrow 가능.
 */
@Slf4j
@Configuration
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@ConditionalOnProperty(name = "security.auth.enabled", havingValue = "true")
public class WebMvcImpersonationConfig implements WebMvcConfigurer {

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        log.info("ImpersonationInterceptor ENABLED — gateway headers (X-Forwarded-User/Groups/Extra-*) "
                + "will be propagated to K8s API via starter Impersonation SPI.");
        registry.addInterceptor(new ImpersonationInterceptor()).addPathPatterns("/**");
    }
}
