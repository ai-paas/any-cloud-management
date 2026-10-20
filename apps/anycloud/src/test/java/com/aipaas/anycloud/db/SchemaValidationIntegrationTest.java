package com.aipaas.anycloud.db;

import static org.assertj.core.api.Assertions.assertThat;

import com.aipaas.anycloud.testsupport.AbstractIntegrationTest;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.containers.MariaDBContainer;

/**
 * Flyway 로 만든 schema 를 entity 매핑이 ddl-auto=validate 로 통과하는지 검증.
 *
 * <p>운영 (application-docker.yaml) 과 같은 조합이다. 다른 테스트들은 ddl-auto 가 create-drop
 * 이거나 none 이라 이 경로를 타지 않아, entity 와 migration 이 어긋나도 부팅 시점에야 드러난다.
 *
 * <p>회귀 대상: ClusterEntity.tags 에 {@code columnDefinition = "json"} 이 붙어 기동이 실패했다.
 * MariaDB 의 JSON 은 네이티브 타입이 아니라 {@code LONGTEXT CHECK (json_valid(..))} 의 별칭이라
 * validate 가 longtext 를 찾고 json 을 기대해 거부한다. MySQL 이면 통과하므로 dialect 를 바꾸면
 * 재현되지 않는다.
 *
 * <p>{@link FlywayMigrationIntegrationTest} 와 같은 이유로 전용 컨테이너를 쓴다. 공유 컨테이너면
 * migrate 가 다른 context 의 데이터를 지운다.
 */
@SpringBootTest
@ActiveProfiles("test")
@ExtendWith(AbstractIntegrationTest.DockerAvailableCondition.class)
@TestPropertySource(
        properties = {
            "spring.flyway.enabled=true",
            // 운영과 동일: Flyway 가 schema 를 만들고 JPA 는 매핑만 검증한다.
            "spring.jpa.hibernate.ddl-auto=validate"
        })
class SchemaValidationIntegrationTest {

    static final MariaDBContainer<?> VALIDATE_DB = new MariaDBContainer<>("mariadb:10.11")
            .withDatabaseName("anycloud_validate_test")
            .withUsername("test")
            .withPassword("test");

    @DynamicPropertySource
    static void register(DynamicPropertyRegistry registry) {
        if (!VALIDATE_DB.isRunning()) {
            VALIDATE_DB.start();
        }
        registry.add("spring.datasource.url", VALIDATE_DB::getJdbcUrl);
        registry.add("spring.datasource.username", VALIDATE_DB::getUsername);
        registry.add("spring.datasource.password", VALIDATE_DB::getPassword);
        registry.add("spring.datasource.driver-class-name", () -> "org.mariadb.jdbc.Driver");
    }

    @Autowired
    private DataSource dataSource;

    /**
     * context 가 떴다는 것 자체가 validate 통과를 뜻한다. 불일치가 있으면 @SpringBootTest 가
     * SchemaManagementException 으로 실패해 이 메서드에 도달하지 못한다.
     */
    @Test
    void entityMappings_matchFlywaySchema() {
        assertThat(dataSource).isNotNull();
    }

    /** MariaDB 가 JSON 을 longtext 로 보고한다는 전제 자체를 고정한다. 이 전제가 깨지면 위 주석이 거짓이 된다. */
    @Test
    void mariadbReportsJsonColumnAsLongtext() {
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);

        String columnType = jdbc.queryForObject(
                "SELECT COLUMN_TYPE FROM INFORMATION_SCHEMA.COLUMNS "
                        + "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'cluster' AND COLUMN_NAME = 'tags'",
                String.class);
        assertThat(columnType).isEqualTo("longtext");

        Integer jsonCheck = jdbc.queryForObject(
                "SELECT COUNT(*) FROM INFORMATION_SCHEMA.CHECK_CONSTRAINTS "
                        + "WHERE CONSTRAINT_SCHEMA = DATABASE() AND TABLE_NAME = 'cluster' "
                        + "  AND CHECK_CLAUSE LIKE '%json_valid%'",
                Integer.class);
        assertThat(jsonCheck).as("JSON 별칭이면 json_valid CHECK 제약이 붙는다").isPositive();
    }
}
