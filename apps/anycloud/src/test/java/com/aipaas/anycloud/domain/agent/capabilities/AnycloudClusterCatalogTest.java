package com.aipaas.anycloud.domain.agent.capabilities;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.aipaas.anycloud.domain.agent.ClusterAgentEntity;
import com.aipaas.anycloud.domain.agent.ClusterAgentRepository;
import com.aipaas.anycloud.domain.agent.model.ClusterAgentStatus;
import com.aipaas.anycloud.testsupport.AbstractUnitTest;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

/**
 * cluster-observability 의 ClusterCatalog SPI 를 anycloud cluster_agent 위에 구현한 adapter 회귀.
 *
 * <p>핵심 보장:
 * <ol>
 *   <li>ACTIVE 상태 agent 가 있는 cluster 만 포함</li>
 *   <li>같은 cluster 에 HA 로 ACTIVE 행 여러 개 있어도 distinct cluster_name</li>
 *   <li>비-ACTIVE (REGISTERED / FAILED 등) 는 제외</li>
 * </ol>
 */
class AnycloudClusterCatalogTest extends AbstractUnitTest {

    private ClusterAgentRepository repo;
    private AnycloudClusterCatalog catalog;

    @BeforeEach
    void setUp() {
        repo = Mockito.mock(ClusterAgentRepository.class);
        catalog = new AnycloudClusterCatalog(repo);
    }

    private ClusterAgentEntity agent(String cluster, String instance, ClusterAgentStatus status) {
        return ClusterAgentEntity.builder()
                .agentId(instance)
                .clusterName(cluster)
                .agentInstanceId(instance)
                .identityTokenHash("h")
                .status(status)
                .build();
    }
    // mock 도 DB-side 필터링과 동일하게 ACTIVE row 만 반환.

    @Test
    void listClusterNames_filtersNonActive() {
        // findByStatus(ACTIVE) 는 ACTIVE 만 반환 — non-ACTIVE 는 DB 가 미리 거름.
        when(repo.findByStatus(ClusterAgentStatus.ACTIVE))
                .thenReturn(List.of(
                        agent("c1", "i1", ClusterAgentStatus.ACTIVE), agent("c4", "i1", ClusterAgentStatus.ACTIVE)));

        List<String> result = catalog.listClusterNames();

        assertThat(result).containsExactlyInAnyOrder("c1", "c4");
    }

    @Test
    void listClusterNames_distinct_whenHAReplicas() {
        // HA: c1 의 instance-1, instance-2 둘 다 ACTIVE.
        when(repo.findByStatus(ClusterAgentStatus.ACTIVE))
                .thenReturn(List.of(
                        agent("c1", "instance-1", ClusterAgentStatus.ACTIVE),
                        agent("c1", "instance-2", ClusterAgentStatus.ACTIVE),
                        agent("c2", "instance-1", ClusterAgentStatus.ACTIVE)));

        List<String> result = catalog.listClusterNames();

        assertThat(result).containsExactlyInAnyOrder("c1", "c2");
    }

    @Test
    void listClusterNames_emptyRepo_returnsEmpty() {
        when(repo.findByStatus(ClusterAgentStatus.ACTIVE)).thenReturn(List.of());

        assertThat(catalog.listClusterNames()).isEmpty();
    }

    @Test
    void listClusterNames_allNonActive_returnsEmpty() {
        // DB-side filter — non-ACTIVE 만 있으면 findByStatus(ACTIVE) 는 빈 리스트.
        when(repo.findByStatus(ClusterAgentStatus.ACTIVE)).thenReturn(List.of());

        assertThat(catalog.listClusterNames()).isEmpty();
    }
}
