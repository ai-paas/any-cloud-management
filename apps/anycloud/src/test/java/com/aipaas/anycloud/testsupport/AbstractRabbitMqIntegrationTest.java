package com.aipaas.anycloud.testsupport;

import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MariaDBContainer;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * MariaDB + RabbitMQ 두 컨테이너를 함께 띄우는 통합 테스트 base.
 *
 * <p>RabbitMQ topology (workflow / addon / agent-forward) 를 실제로 검증하는 통합 테스트가 본 class
 * 를 extend. {@link AbstractIntegrationTest} 와 동일한 Docker 가용성 check 패턴.
 *
 * <p>예시 사용:
 * <pre>{@code
 * class AddonInstallFlowIntegrationTest extends AbstractRabbitMqIntegrationTest {
 *     @Autowired AddonInstallPublisher publisher;
 *     @Autowired ClusterAddonRepository repo;
 *
 *     @Test void install_addon_publishesToQueue() {
 *         publisher.publish(addonId);
 *         // RabbitMQ 통해 listener 가 처리 — 실제 broker 동작 검증
 *     }
 * }
 * }</pre>
 *
 * <p>container reuse 활성화: {@code ~/.testcontainers.properties} 에 다음 추가
 * <pre>testcontainers.reuse.enable=true</pre>
 * 로컬 dev 에선 매 test 마다 container 재생성 회피 — 5-10초 절감.
 */
@SpringBootTest
@ActiveProfiles("test")
@ExtendWith(AbstractIntegrationTest.DockerAvailableCondition.class)
public abstract class AbstractRabbitMqIntegrationTest {

    static final MariaDBContainer<?> MARIA_DB = new MariaDBContainer<>("mariadb:10.11")
            .withDatabaseName("anycloud_test")
            .withUsername("test")
            .withPassword("test")
            .withReuse(true);

    static final RabbitMQContainer RABBITMQ =
            new RabbitMQContainer(DockerImageName.parse("rabbitmq:3.13-management")).withReuse(true);

    @DynamicPropertySource
    static void register(DynamicPropertyRegistry registry) {
        if (!MARIA_DB.isRunning()) {
            MARIA_DB.start();
        }
        if (!RABBITMQ.isRunning()) {
            RABBITMQ.start();
        }
        // DB
        registry.add("spring.datasource.url", MARIA_DB::getJdbcUrl);
        registry.add("spring.datasource.username", MARIA_DB::getUsername);
        registry.add("spring.datasource.password", MARIA_DB::getPassword);
        registry.add("spring.datasource.driver-class-name", () -> "org.mariadb.jdbc.Driver");
        // RabbitMQ
        registry.add("spring.rabbitmq.host", RABBITMQ::getHost);
        registry.add("spring.rabbitmq.port", RABBITMQ::getAmqpPort);
        registry.add("spring.rabbitmq.username", RABBITMQ::getAdminUsername);
        registry.add("spring.rabbitmq.password", RABBITMQ::getAdminPassword);
        registry.add("spring.rabbitmq.virtual-host", () -> "/");
    }
}
