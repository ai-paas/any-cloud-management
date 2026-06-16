package com.aipaas.anycloud.configuration.web;

import com.aipaas.anycloud.common.web.AuditInterceptor;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Audit / 기타 인터셉터 등록. AuditInterceptor 가 toggle 로 비활성될 수 있어
 * {@link ObjectProvider} 로 optional 주입.
 */
@Configuration
@RequiredArgsConstructor
public class WebMvcInterceptorConfig implements WebMvcConfigurer {

    private final ObjectProvider<AuditInterceptor> auditInterceptorProvider;

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        AuditInterceptor auditInterceptor = auditInterceptorProvider.getIfAvailable();
        if (auditInterceptor != null) {
            registry.addInterceptor(auditInterceptor)
                    .excludePathPatterns(
                            "/actuator/**", // health/metric 폴링은 잡음
                            "/v3/api-docs/**",
                            "/swagger-ui/**",
                            "/swagger-ui.html");
        }
    }
}
