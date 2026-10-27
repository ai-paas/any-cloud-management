package com.aipaas.anycloud.domain.agent;

import static org.assertj.core.api.Assertions.assertThat;

import com.aipaas.anycloud.domain.agent.model.ClusterAgentStatus;
import com.aipaas.anycloud.testsupport.AbstractIntegrationTest;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * 파생 삭제 쿼리는 엔티티를 조회해 {@code remove} 를 호출하므로 트랜잭션이 필요하다.
 * {@code SimpleJpaRepository.delete} 와 달리 자동으로 붙지 않아, 트랜잭션 없는 호출자에서
 * "No EntityManager with actual transaction available" 로 실패했다. VM destroy 가 그 경로였고
 * CSP 자원을 지운 뒤 단계가 FAILED 로 기록됐다.
 *
 * <p>mock 저장소로는 재현되지 않아 실제 EntityManager 가 필요하다.
 */
class AgentRowCleanupNeedsItsOwnTransactionTest extends AbstractIntegrationTest {

    @Autowired
    ClusterAgentRepository repository;

    private ClusterAgentEntity row(String agentId, String clusterName) {
        return ClusterAgentEntity.builder()
                .agentId(agentId)
                .clusterName(clusterName)
                .agentInstanceId("instance-" + agentId)
                .identityTokenHash("hash-" + agentId)
                .status(ClusterAgentStatus.DEGRADED)
                .lastSeenAt(LocalDateTime.now())
                .build();
    }

    @Test
    void deleteByClusterName_runsWithoutAnAmbientTransaction() {
        repository.save(row("tx-a", "tx-cluster"));
        repository.save(row("tx-b", "tx-cluster"));
        repository.save(row("tx-c", "other-cluster"));

        long deleted = repository.deleteByClusterName("tx-cluster");

        assertThat(deleted).isEqualTo(2);
        assertThat(repository.findByClusterName("tx-cluster")).isEmpty();
        assertThat(repository.findByClusterName("other-cluster")).hasSize(1);

        repository.deleteByClusterName("other-cluster");
    }
}
