package com.aipaas.anycloud.domain.agent.upgrade;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.aipaas.anycloud.common.error.enums.ErrorCode;
import com.aipaas.anycloud.common.error.exception.CustomException;
import com.aipaas.anycloud.domain.agent.ClusterAgentEntity;
import com.aipaas.anycloud.domain.agent.ClusterAgentRepository;
import com.aipaas.anycloud.domain.agent.model.ClusterAgentStatus;
import com.aipaas.anycloud.domain.agent.model.ClusterAgentUpgradeStatus;
import com.aipaas.anycloud.domain.agent.upgrade.AgentUpgradeService.UpgradeResult;
import com.aipaas.anycloud.domain.kube.KubeService;
import com.aipaas.anycloud.testsupport.AbstractUnitTest;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;

class AgentUpgradeServiceTest extends AbstractUnitTest {

    @Mock
    ClusterAgentRepository repository;

    @Mock
    KubeService kubeService;

    private AgentUpgradeServiceImpl service;

    @BeforeEach
    void setUp() {
        // @Value → AgentProperties. test 는 default 값 사용 (record 가 자동 채움).
        com.aipaas.anycloud.domain.agent.AgentProperties props =
                new com.aipaas.anycloud.domain.agent.AgentProperties(null, null, null, null, null);
        service = new AgentUpgradeServiceImpl(repository, kubeService, props);
    }

    @Test
    void upgradeCluster_activeAgentIdle_triggersAndMarksInProgress() {
        ClusterAgentEntity active = activeRow("alpha", "v0.9.0", ClusterAgentUpgradeStatus.IDLE);
        when(repository.findByClusterName("alpha")).thenReturn(List.of(active));

        UpgradeResult result = service.upgradeCluster("alpha", "aipaas/cluster-agent:v1.0.0");

        assertThat(result.status()).isEqualTo("IN_PROGRESS");
        assertThat(active.getUpgradeStatus()).isEqualTo(ClusterAgentUpgradeStatus.IN_PROGRESS);
        assertThat(active.getUpgradeTargetImage()).isEqualTo("aipaas/cluster-agent:v1.0.0");
        assertThat(active.getUpgradeSourceVersion()).isEqualTo("v0.9.0");
        // PENDING + IN_PROGRESS save 2번.
        verify(repository, times(2)).save(active);
        // agent applyResource 호출됨.
        verify(kubeService).applyResource(anyString(), anyString(), anyString());
    }

    @Test
    void upgradeCluster_alreadyOnTarget_returnsNoOp() {
        ClusterAgentEntity active = activeRow("alpha", "v1.0.0", ClusterAgentUpgradeStatus.SUCCEEDED);
        when(repository.findByClusterName("alpha")).thenReturn(List.of(active));

        UpgradeResult result = service.upgradeCluster("alpha", "aipaas/cluster-agent:v1.0.0");

        assertThat(result.status()).isEqualTo("NO_OP");
        // no-op 면 status 변경 / apply 호출 둘 다 없어야 함.
        verify(repository, never()).save(any());
        verify(kubeService, never()).applyResource(anyString(), anyString(), anyString());
    }

    @Test
    void upgradeCluster_noActiveAgent_rejects() {
        ClusterAgentEntity degraded = activeRow("alpha", "v0.9.0", ClusterAgentUpgradeStatus.IDLE);
        degraded.setStatus(ClusterAgentStatus.DEGRADED);
        when(repository.findByClusterName("alpha")).thenReturn(List.of(degraded));

        assertThatThrownBy(() -> service.upgradeCluster("alpha", "aipaas/cluster-agent:v1.0.0"))
                .isInstanceOf(CustomException.class)
                .hasMessageContaining("No ACTIVE agent");
    }

    @Test
    void upgradeCluster_inflight_rejectsAsDuplicate() {
        ClusterAgentEntity inflight = activeRow("alpha", "v0.9.0", ClusterAgentUpgradeStatus.IN_PROGRESS);
        inflight.setUpgradeTargetImage("aipaas/cluster-agent:v0.9.5");
        when(repository.findByClusterName("alpha")).thenReturn(List.of(inflight));

        assertThatThrownBy(() -> service.upgradeCluster("alpha", "aipaas/cluster-agent:v1.0.0"))
                .isInstanceOf(CustomException.class)
                .hasMessageContaining("Upgrade already in flight");
    }

    @Test
    void upgradeCluster_emptyCluster_rejects() {
        when(repository.findByClusterName("ghost")).thenReturn(List.of());
        assertThatThrownBy(() -> service.upgradeCluster("ghost", "aipaas/cluster-agent:v1.0.0"))
                .isInstanceOf(CustomException.class)
                .hasMessageContaining("No agent rows for cluster ghost");
    }

    @Test
    void upgradeCluster_blankTargetImage_rejects() {
        assertThatThrownBy(() -> service.upgradeCluster("alpha", ""))
                .isInstanceOf(CustomException.class)
                .hasMessageContaining("targetImage required");
    }

    @Test
    void upgradeCluster_applyFailure_marksFailedAndThrows() {
        ClusterAgentEntity active = activeRow("alpha", "v0.9.0", ClusterAgentUpgradeStatus.IDLE);
        when(repository.findByClusterName("alpha")).thenReturn(List.of(active));
        doThrow(new CustomException("Agent unavailable", ErrorCode.AGENT_UNAVAILABLE))
                .when(kubeService)
                .applyResource(anyString(), anyString(), anyString());

        assertThatThrownBy(() -> service.upgradeCluster("alpha", "aipaas/cluster-agent:v1.0.0"))
                .isInstanceOf(CustomException.class)
                .hasMessageContaining("Agent upgrade failed");
        assertThat(active.getUpgradeStatus()).isEqualTo(ClusterAgentUpgradeStatus.FAILED);
        assertThat(active.getUpgradeError()).contains("APPLY_MANIFEST failed");
    }

    private static ClusterAgentEntity activeRow(
            String cluster, String version, ClusterAgentUpgradeStatus upgradeStatus) {
        return ClusterAgentEntity.builder()
                .agentId("agent-" + cluster)
                .clusterName(cluster)
                .agentInstanceId("instance-1")
                .identityTokenHash("hash")
                .status(ClusterAgentStatus.ACTIVE)
                .agentVersion(version)
                .upgradeStatus(upgradeStatus)
                .build();
    }
}
