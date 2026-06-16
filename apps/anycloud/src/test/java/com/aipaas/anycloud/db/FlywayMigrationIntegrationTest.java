package com.aipaas.anycloud.db;

import static org.assertj.core.api.Assertions.assertThat;

import com.aipaas.anycloud.testsupport.AbstractIntegrationTest;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Flyway migration 전체 적용 회귀 방지.
 *
 * <p>특히 V33 (backup_history) 의 FK constraint 가 cluster table 의 collation 과 매칭되는지 검증
 * (errno 150 charset/collation 불일치 회귀 방지).
 */
class FlywayMigrationIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private DataSource dataSource;

    @Test
    void allMigrations_appliedToLatestVersion() {
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);

        // flyway_schema_history 의 최신 version row.
        Integer maxVersion = jdbc.queryForObject(
                "SELECT MAX(CAST(REPLACE(version, '.', '') AS UNSIGNED)) "
                        + "FROM flyway_schema_history WHERE success = 1",
                Integer.class);

        assertThat(maxVersion).as("최소 V33 까지 apply 되어야 함").isNotNull().isGreaterThanOrEqualTo(33);
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
