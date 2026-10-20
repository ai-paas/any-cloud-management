package com.aipaas.anycloud.configuration.persistence;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.flyway.FlywayMigrationStrategy;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

/**
 * dev profile 에서만 부팅마다 {@code repair()} 를 먼저 부르고 {@code migrate()} 합니다.
 *
 * <p>migration 중간에 죽으면 flyway_schema_history 에 failed row 가 남아 다음 부팅이
 * ValidateException 으로 거부됩니다. dev 에서는 그 행을 매번 직접 지워야 합니다.
 *
 * <p>운영에는 이 bean 이 없습니다. 깨진 history 는 사람이 봐야 하므로 부팅이 실패하는 게 맞습니다.
 */
@Slf4j
@Configuration
@Profile("dev")
public class DevFlywayMigrationStrategy {

    /**
     * bean 이름을 클래스 이름과 다르게 둡니다. devtools restart 가 main 과 restart classloader
     * 양쪽에서 @Configuration 을 읽어 같은 이름이면 두 번 등록되며 충돌합니다.
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
