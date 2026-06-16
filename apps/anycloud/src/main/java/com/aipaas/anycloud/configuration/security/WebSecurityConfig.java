package com.aipaas.anycloud.configuration.security;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

/**
 * 정적 토큰 인증 SecurityFilterChain — {@code security.auth.enabled=true} 일 때만 활성.
 * OFF 일 때는 {@link WebSecurityDisabledConfig} 가 permitAll chain 으로 대체.
 */
@Slf4j
@Configuration
@RequiredArgsConstructor
// (follow-up): worker mode (web-application-type=none) 에서 HttpSecurity bean
// 없어 fail 회피 — servlet web 일 때만 활성.
@org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication(
        type = org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication.Type.SERVLET)
@ConditionalOnProperty(name = "security.auth.enabled", havingValue = "true")
public class WebSecurityConfig {

    private final SecurityProperties properties;

    @PostConstruct
    void validate() {
        if (properties.getToken() == null || properties.getToken().isBlank()) {
            throw new IllegalStateException("security.auth.enabled=true 이지만 security.auth.token이 비어 있습니다. "
                    + "SECURITY_AUTH_TOKEN 환경변수를 설정하거나 토글을 끄세요.");
        }
        log.info(
                "Static token auth ENABLED. header={}, scheme={}, publicPaths={}",
                properties.getHeader(),
                properties.getScheme(),
                properties.getPublicPaths());
    }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        StaticTokenAuthFilter tokenFilter = new StaticTokenAuthFilter(properties);

        http.csrf(AbstractHttpConfigurer::disable)
                .cors(cors -> {})
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .formLogin(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                .authorizeHttpRequests(
                        auth -> auth.requestMatchers(properties.getPublicPaths().toArray(String[]::new))
                                .permitAll()
                                .anyRequest()
                                .authenticated())
                .addFilterBefore(tokenFilter, UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }
}
