package com.aipaas.anycloud.configuration.security;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/** {@link ImpersonationInterceptor} 등록 — {@code security.auth.enabled=true} 일 때만 활성. */
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
