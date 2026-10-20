package com.aipaas.anycloud.db;

import static org.assertj.core.api.Assertions.assertThat;

import com.aipaas.anycloud.testsupport.AbstractIntegrationTest;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.containers.MariaDBContainer;

/**
 * Flyway migration 전체 적용 회귀 방지.
 *
 * <p>backup_history 의 FK constraint 와 cluster table 의 collation 일치 여부 검증
 * (errno 150 charset, collation 불일치 회귀 방지).
 *
 * <p>{@link AbstractIntegrationTest} 를 상속하지 않고 전용 컨테이너 사용. 공유 컨테이너 사용 시
 * 이 테스트의 migrate 가 다른 context 의 데이터를 삭제하고 baseline seed 를 남겨, 이미 기동한
 * 다른 테스트가 해당 행을 조회. context 생성 순서와 테스트 실행 순서가 달라 순서로는 차단 불가.
 */
@SpringBootTest
@ActiveProfiles("test")
@ExtendWith(AbstractIntegrationTest.DockerAvailableCondition.class)
@TestPropertySource(
        properties = {
            // test profile 기본값은 Flyway off, ddl-auto=create-drop (엔티티 기반 스키마 생성).
            // 이 테스트는 Flyway 실행이 전제이므로 설정 반전.
            "spring.flyway.enabled=true",
            "spring.jpa.hibernate.ddl-auto=none"
        })
class FlywayMigrationIntegrationTest {

    static final MariaDBContainer<?> FLYWAY_DB = new MariaDBContainer<>("mariadb:10.11")
            .withDatabaseName("anycloud_flyway_test")
            .withUsername("test")
            .withPassword("test");

    @DynamicPropertySource
    static void register(DynamicPropertyRegistry registry) {
        if (!FLYWAY_DB.isRunning()) {
            FLYWAY_DB.start();
        }
        registry.add("spring.datasource.url", FLYWAY_DB::getJdbcUrl);
        registry.add("spring.datasource.username", FLYWAY_DB::getUsername);
        registry.add("spring.datasource.password", FLYWAY_DB::getPassword);
        registry.add("spring.datasource.driver-class-name", () -> "org.mariadb.jdbc.Driver");
    }

    @Autowired
    private DataSource dataSource;

    @Test
    void allMigrations_appliedToLatestVersion() throws Exception {
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);

        Integer failed =
                jdbc.queryForObject("SELECT COUNT(*) FROM flyway_schema_history WHERE success = 0", Integer.class);
        assertThat(failed).as("실패한 migration 이 없어야 함").isZero();

        // 특정 버전 번호 고정 시 migration 의 baseline 통합마다 실패
        // (v0.3.0 에서 V33 → V1 baseline 통합 발생).
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
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);

        Integer tableExists = jdbc.queryForObject(
                "SELECT COUNT(*) FROM INFORMATION_SCHEMA.TABLES "
                        + "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'backup_history'",
                Integer.class);
        assertThat(tableExists).isEqualTo(1);

        Integer fkCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM INFORMATION_SCHEMA.KEY_COLUMN_USAGE WHERE TABLE_SCHEMA = DATABASE() "
                        + "  AND TABLE_NAME = 'backup_history' "
                        + "  AND CONSTRAINT_NAME = 'fk_backup_history_cluster'",
                Integer.class);
        assertThat(fkCount).as("fk_backup_history_cluster FK 가 정의되어야 함").isEqualTo(1);
    }

    @Test
    void clusterAddon_table_exists() {
        // cluster_addon.id 길이 확장(36 -> 64) 회귀 방지.
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);

        String idType = jdbc.queryForObject(
                "SELECT COLUMN_TYPE FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_SCHEMA = DATABASE() "
                        + "  AND TABLE_NAME = 'cluster_addon' "
                        + "  AND COLUMN_NAME = 'id'",
                String.class);

        assertThat(idType).as("id column 길이 확장 (36 -> 64)").containsIgnoringCase("varchar(64)");
    }
}
