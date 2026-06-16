package com.aipaas.anycloud.testsupport;

import org.junit.jupiter.api.extension.ConditionEvaluationResult;
import org.junit.jupiter.api.extension.ExecutionCondition;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.MariaDBContainer;

/**
 * 전체 Spring context + Testcontainers MariaDB 기반 통합 테스트. context 부팅 비용이 크므로
 * 동일 JVM 의 여러 테스트가 한 컨테이너를 공유하도록 static 으로 관리.
 * <p>
 * Docker 가 없거나 client 버전 불일치 등으로 컨테이너를 초기화할 수 없으면 클래스 전체를
 * "skipped" 로 표기한다(실패 아님). {@link DockerAvailableCondition} 가 모든 테스트 시작 전에
 * Docker 가용성을 한 번 확인.
 *
 * <pre>{@code
 * class MyIntegrationTest extends AbstractIntegrationTest {
 *     @Autowired MyService svc;
 *     @Test void scenario() { ... }
 * }
 * }</pre>
 */
@SpringBootTest
@ActiveProfiles("test")
@ExtendWith(AbstractIntegrationTest.DockerAvailableCondition.class)
public abstract class AbstractIntegrationTest {

    // static container — 같은 JVM 의 모든 테스트가 공유 (Testcontainers reuse).
    // withReuse(true) 는 ~/.testcontainers.properties 에 testcontainers.reuse.enable=true 가 있어야 작동.
    static final MariaDBContainer<?> MARIA_DB = new MariaDBContainer<>("mariadb:10.11")
            .withDatabaseName("anycloud_test")
            .withUsername("test")
            .withPassword("test")
            .withReuse(true);

    @DynamicPropertySource
    static void register(DynamicPropertyRegistry registry) {
        // Docker available 일 때만 evaluator 가 통과하여 이 메서드까지 도달. 안전하게 start.
        if (!MARIA_DB.isRunning()) {
            MARIA_DB.start();
        }
        registry.add("spring.datasource.url", MARIA_DB::getJdbcUrl);
        registry.add("spring.datasource.username", MARIA_DB::getUsername);
        registry.add("spring.datasource.password", MARIA_DB::getPassword);
        registry.add("spring.datasource.driver-class-name", () -> "org.mariadb.jdbc.Driver");
    }

    /**
     * Docker 가 사용 불가하면 클래스 전체를 skipped 로 표시(실패 아님). 결과 한 번만 캐시.
     */
    public static class DockerAvailableCondition implements ExecutionCondition {
        private static volatile Boolean cached;

        @Override
        public ConditionEvaluationResult evaluateExecutionCondition(ExtensionContext context) {
            boolean available = isDockerAvailable();
            return available
                    ? ConditionEvaluationResult.enabled("Docker available — integration test runs")
                    : ConditionEvaluationResult.disabled("Docker unavailable — integration test skipped");
        }

        private static boolean isDockerAvailable() {
            Boolean snapshot = cached;
            if (snapshot != null) {
                return snapshot;
            }
            try {
                boolean ok = DockerClientFactory.instance().isDockerAvailable();
                cached = ok;
                return ok;
            } catch (Throwable t) {
                cached = false;
                return false;
            }
        }
    }
}
