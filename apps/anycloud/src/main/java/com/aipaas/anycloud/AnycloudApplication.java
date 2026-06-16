package com.aipaas.anycloud;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 백엔드 부트스트랩.
 * <p>
 * 인증은 {@code security.auth.enabled} 토글로 제어한다. OFF(기본)일 때는
 * {@code WebSecurityConfig}가 등록되지 않아 Spring Security 기본 자동설정이 모든 요청을 통과시킨다.
 * ON일 때 정적 토큰(Authorization: Bearer ...) 검증이 활성화된다.
 */
@SpringBootApplication
@ComponentScan(basePackages = "com.aipaas.anycloud")
@ConfigurationPropertiesScan(basePackages = "com.aipaas.anycloud")
@Configuration
@EnableScheduling
public class AnycloudApplication {
    public static void main(String[] args) {
        SpringApplication.run(AnycloudApplication.class, args);
    }
}
