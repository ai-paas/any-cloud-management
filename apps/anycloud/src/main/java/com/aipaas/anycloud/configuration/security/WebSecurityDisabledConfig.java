package com.aipaas.anycloud.configuration.security;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;

/**
 * {@code security.auth.enabled=false} 일 때 활성. spring-boot-starter-security 가 classpath 에
 * 있는 한 Spring Boot 가 default form login chain 을 자동 등록 — 모든 endpoint 가 login 페이지
 * 로 redirect. 본 bean 이 그 default 를 override 해서 전체 permitAll.
 *
 * <p>gateway 뒤에서 backend 자체 인증 토글을 OFF 로 두는 운영 모델 대응.
 *
 * <p> (follow-up): {@code @ConditionalOnWebApplication(SERVLET)} 추가
 * worker mode ({@code APP_ROLE=worker}, {@code spring.main.web-application-type=none}) 에서는
 * HttpSecurity bean 자체가 없어 본 config 가 fail 했었음. servlet web 일 때만 활성.
 */
@Slf4j
@Configuration
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@ConditionalOnProperty(name = "security.auth.enabled", havingValue = "false", matchIfMissing = true)
public class WebSecurityDisabledConfig {

    @Bean
    public SecurityFilterChain permitAllFilterChain(HttpSecurity http) throws Exception {
        log.info("Backend auth toggle = OFF — Spring Security 전체 permitAll (gateway 모드).");
        http.csrf(AbstractHttpConfigurer::disable)
                .cors(cors -> {})
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .formLogin(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                .authorizeHttpRequests(auth -> auth.anyRequest().permitAll());
        return http.build();
    }
}
