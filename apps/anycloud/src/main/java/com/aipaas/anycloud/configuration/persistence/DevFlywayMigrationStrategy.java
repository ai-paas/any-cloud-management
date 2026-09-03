package com.aipaas.anycloud.configuration.persistence;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.flyway.FlywayMigrationStrategy;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

/**
 * dev profile 전용 — 매 부팅 시 {@code flyway.repair()} 호출 후 {@code migrate()}.
 *
 * <p>이전 부팅이 V1 같은 migration 중간에 죽으면 flyway_schema_history 에 failed row 가
 * 남아서 다음 부팅이 ValidateException 으로 거부됨. dev iteration 흐름 끊김을 막기 위해 repair
 * 를 자동 선행 — failed row 삭제.
 *
 * <p>운영(default profile / prod) 에선 본 bean 미적용 → Spring Boot 의 표준 검증 그대로
 * (broken history 면 즉시 부팅 실패). 운영에선 사람 손이 들어가야 하기 때문에 안전.
 */
@Slf4j
@Configuration
@Profile("dev")
public class DevFlywayMigrationStrategy {

    /**
     * Bean 이름은 클래스 이름과 다르게 — devtools restart 가 main + restart classloader
     * 양쪽에서 @Configuration 을 로드하는 dev 시나리오에서 같은 이름의 bean 이 두 번
     * 등록되는 충돌 회피.
     */
    @Bean
    public FlywayMigrationStrategy devFlywayRepairAndMigrate() {
        return flyway -> {
            log.warn("[dev profile] Flyway repair() 자동 선행 — failed history row 제거 후 migrate.");
            flyway.repair();
            flyway.migrate();
        };
    }
}
