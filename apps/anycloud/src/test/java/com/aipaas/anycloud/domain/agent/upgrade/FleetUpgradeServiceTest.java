package com.aipaas.anycloud.domain.agent.upgrade;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.aipaas.anycloud.common.error.exception.CustomException;
import com.aipaas.anycloud.domain.agent.ClusterAgentEntity;
import com.aipaas.anycloud.domain.agent.ClusterAgentRepository;
import com.aipaas.anycloud.domain.agent.model.ClusterAgentUpgradeWave;
import com.aipaas.anycloud.domain.agent.upgrade.FleetUpgradeService.FleetPreview;
import com.aipaas.anycloud.testsupport.AbstractUnitTest;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;

class FleetUpgradeServiceTest extends AbstractUnitTest {

    @Mock
    ClusterAgentRepository repository;

    private FleetUpgradeServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new FleetUpgradeServiceImpl(repository);
    }

    @Test
    void preview_groupsByWave_dedupClusters_aggregatesVersions() {
        when(repository.findAll())
                .thenReturn(List.of(
                        row("alpha", ClusterAgentUpgradeWave.CANARY, "v1.0.0"),
                        row("alpha", ClusterAgentUpgradeWave.CANARY, "v1.0.0"), // HA replica — dedup
                        row("bravo", ClusterAgentUpgradeWave.GENERAL, "v0.9.0"),
                        row("charlie", ClusterAgentUpgradeWave.GENERAL, "v1.0.0"),
                        row("delta", ClusterAgentUpgradeWave.PAUSED, "v0.9.0")));

        FleetPreview p = service.preview();

        assertThat(p.totalClusters()).isEqualTo(4);
        assertThat(p.waveCounts())
                .containsEntry(ClusterAgentUpgradeWave.CANARY, 1L)
                .containsEntry(ClusterAgentUpgradeWave.GENERAL, 2L)
                .containsEntry(ClusterAgentUpgradeWave.PAUSED, 1L);
        assertThat(p.versionCounts()).containsEntry("v1.0.0", 2L).containsEntry("v0.9.0", 2L);

        // 정렬: CANARY → GENERAL → PAUSED (orderRank 기준)
        assertThat(p.byWave().keySet())
                .containsExactly(
                        ClusterAgentUpgradeWave.CANARY,
                        ClusterAgentUpgradeWave.GENERAL,
                        ClusterAgentUpgradeWave.PAUSED);
    }

    @Test
    void preview_emptyFleet_returnsZeros() {
        when(repository.findAll()).thenReturn(List.of());
        FleetPreview p = service.preview();
        assertThat(p.totalClusters()).isZero();
        assertThat(p.waveCounts()).isEmpty();
        assertThat(p.versionCounts()).isEmpty();
    }

    @Test
    void setWave_updatesAllHaReplicas() {
        ClusterAgentEntity r1 = row("alpha", ClusterAgentUpgradeWave.GENERAL, "v1.0.0");
        ClusterAgentEntity r2 = row("alpha", ClusterAgentUpgradeWave.GENERAL, "v1.0.0");
        when(repository.findByClusterName("alpha")).thenReturn(List.of(r1, r2));

        service.setWave("alpha", ClusterAgentUpgradeWave.CANARY);

        assertThat(r1.getUpgradeWave()).isEqualTo(ClusterAgentUpgradeWave.CANARY);
        assertThat(r2.getUpgradeWave()).isEqualTo(ClusterAgentUpgradeWave.CANARY);
        verify(repository).saveAll(anyList());
    }

    @Test
    void setWave_unknownCluster_throwsEntityNotFound() {
        when(repository.findByClusterName("ghost")).thenReturn(List.of());
        assertThatThrownBy(() -> service.setWave("ghost", ClusterAgentUpgradeWave.CANARY))
                .isInstanceOf(CustomException.class)
                .hasMessageContaining("No agent rows for cluster ghost");
    }

    @Test
    void setWave_blankClusterName_rejects() {
        assertThatThrownBy(() -> service.setWave("", ClusterAgentUpgradeWave.CANARY))
                .isInstanceOf(CustomException.class)
                .hasMessageContaining("cluster name required");
    }

    @Test
    void setWave_nullWave_rejects() {
        assertThatThrownBy(() -> service.setWave("alpha", null))
                .isInstanceOf(CustomException.class)
                .hasMessageContaining("wave required");
    }

    private static ClusterAgentEntity row(String cluster, ClusterAgentUpgradeWave wave, String version) {
        return ClusterAgentEntity.builder()
                .clusterName(cluster)
                .agentInstanceId("instance-" + System.nanoTime())
                .upgradeWave(wave)
                .agentVersion(version)
                .identityTokenHash("hash")
                .build();
    }
}
