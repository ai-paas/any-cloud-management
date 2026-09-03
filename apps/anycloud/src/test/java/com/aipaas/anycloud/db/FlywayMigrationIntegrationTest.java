package com.aipaas.anycloud.db;

import static org.assertj.core.api.Assertions.assertThat;

import com.aipaas.anycloud.testsupport.AbstractIntegrationTest;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.flyway.FlywayMigrationStrategy;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

/**
 * Flyway migration 전체 적용 회귀 방지.
 *
 * <p>특히 V33 (backup_history) 의 FK constraint 가 cluster table 의 collation 과 매칭되는지 검증
 * (errno 150 charset/collation 불일치 회귀 방지).
 */
@TestPropertySource(
        properties = {
            // test profile 은 기본적으로 Flyway off + ddl-auto=create-drop 이다 (엔티티에서 스키마 생성).
            // 이 테스트만은 Flyway 가 실제로 돌아야 검증 대상이 생기므로 반대로 뒤집는다.
            "spring.flyway.enabled=true",
            "spring.flyway.clean-disabled=false",
            "spring.jpa.hibernate.ddl-auto=none"
        })
@Import(FlywayMigrationIntegrationTest.CleanMigrateConfig.class)
class FlywayMigrationIntegrationTest extends AbstractIntegrationTest {

    /**
     * MariaDB 컨테이너는 같은 JVM 의 모든 통합 테스트가 공유한다. 다른 context 가 먼저 떠서
     * ddl-auto=create-drop 으로 테이블을 만들어 두면, Flyway 는 schema history 없는 비어 있지
     * 않은 스키마를 만나 migrate 를 거부한다. 테스트 실행 순서는 보장되지 않으므로 순서에
     * 기대지 않고 clean 후 migrate 한다.
     */
    @TestConfiguration
    static class CleanMigrateConfig {

        @Bean
        FlywayMigrationStrategy cleanBeforeMigrate() {
            return flyway -> {
                flyway.clean();
                flyway.migrate();
            };
        }
    }

    @Autowired
    private DataSource dataSource;

    @Test
    void allMigrations_appliedToLatestVersion() throws Exception {
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);

        // 실패한 migration 이 하나도 없어야 한다.
        Integer failed =
                jdbc.queryForObject("SELECT COUNT(*) FROM flyway_schema_history WHERE success = 0", Integer.class);
        assertThat(failed).as("실패한 migration 이 없어야 함").isZero();

        // classpath 의 V*.sql 개수와 적용된 versioned migration 개수가 일치해야 한다.
        // 특정 버전 번호를 박아두면 migration 이 baseline 으로 통합될 때마다 테스트가 깨진다
        // (v0.3.0 에서 실제로 V33 -> V1 baseline 통합이 일어났다).
        int scriptCount =
                new PathMatchingResourcePatternResolver().getResources("classpath:db/migration/V*.sql").length;
        assertThat(scriptCount).as("migration script 가 하나는 있어야 함").isPositive();

        Integer applied = jdbc.queryForObject(
                "SELECT COUNT(*) FROM flyway_schema_history WHERE success = 1 AND type = 'SQL'", Integer.class);
        assertThat(applied)
                .as("classpath 의 V*.sql %d 개가 모두 적용되어야 함", scriptCount)
                .isEqualTo(scriptCount);
    }

    @Test
    void backupHistory_table_exists_withCorrectFk() {
        // V33 의 backup_history table + cluster_id FK 검증.
        // errno 150 (charset 불일치) 가 발생하면 Flyway startup 자체가 실패하므로 본 test 가 컨텍스트 부팅한 사실 자체가 fix 검증.
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);

        Integer tableExists = jdbc.queryForObject(
                "SELECT COUNT(*) FROM INFORMATION_SCHEMA.TABLES "
                        + "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'backup_history'",
                Integer.class);

        assertThat(tableExists).isEqualTo(1);

        // FK constraint 존재 확인.
        Integer fkCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM INFORMATION_SCHEMA.KEY_COLUMN_USAGE " + "WHERE TABLE_SCHEMA = DATABASE() "
                        + "  AND TABLE_NAME = 'backup_history' "
                        + "  AND CONSTRAINT_NAME = 'fk_backup_history_cluster'",
                Integer.class);

        assertThat(fkCount).as("V33 의 fk_backup_history_cluster FK 가 정의되어야 함").isEqualTo(1);
    }

    @Test
    void clusterAddon_table_exists() {
        // N-1 + V31 (id size expansion) 회귀 — cluster_addon table 의 id column VARCHAR(64).
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);

        String idType = jdbc.queryForObject(
                "SELECT COLUMN_TYPE FROM INFORMATION_SCHEMA.COLUMNS " + "WHERE TABLE_SCHEMA = DATABASE() "
                        + "  AND TABLE_NAME = 'cluster_addon' "
                        + "  AND COLUMN_NAME = 'id'",
                String.class);

        assertThat(idType).as("V31 의 id column 길이 확장 (36 → 64)").containsIgnoringCase("varchar(64)");
    }
}
