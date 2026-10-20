package com.aipaas.anycloud.configuration.persistence;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.flyway.FlywayMigrationStrategy;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

/**
 * dev profile 한정, 부팅마다 {@code repair()} 선행 후 {@code migrate()} 실행.
 *
 * <p>migration 중단 시 flyway_schema_history 에 failed row 가 남아 다음 부팅이
 * ValidateException 으로 거부. dev 에서는 해당 행을 매번 수동 삭제 필요.
 *
 * <p>운영 환경에는 이 bean 미등록. 손상된 history 는 운영자 확인이 필요하므로 부팅 실패가 의도된 동작.
 */
@Slf4j
@Configuration
@Profile("dev")
public class DevFlywayMigrationStrategy {

    /**
     * bean 이름을 클래스 이름과 다르게 지정. devtools restart 가 main 과 restart classloader
     * 양쪽에서 @Configuration 을 읽어 이름이 같으면 중복 등록으로 충돌.
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
