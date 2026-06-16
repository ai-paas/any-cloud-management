package com.aipaas.anycloud.domain.agent.upgrade;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.aipaas.anycloud.common.error.enums.ErrorCode;
import com.aipaas.anycloud.common.error.exception.CustomException;
import com.aipaas.anycloud.domain.agent.AgentProperties;
import com.aipaas.anycloud.domain.agent.ClusterAgentEntity;
import com.aipaas.anycloud.domain.agent.ClusterAgentRepository;
import com.aipaas.anycloud.domain.agent.model.ClusterAgentStatus;
import com.aipaas.anycloud.domain.agent.model.ClusterAgentUpgradeStatus;
import com.aipaas.anycloud.domain.agent.upgrade.AgentUpgradeService.UpgradeResult;
import com.aipaas.anycloud.domain.kube.KubeService;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

/**
 * {@link AgentUpgradeServiceImpl} 회귀 lock —.
 *
 * <p>Fleet upgrade trigger path 의 분기 회귀 lock. 회귀 시 in-flight upgrade 중복 trigger /
 * inactive cluster upgrade 시도 등 운영 incident 가능.
 */
class AgentUpgradeServiceImplTest {

    private ClusterAgentRepository repository;
    private KubeService kubeService;
    private AgentProperties properties;
    private AgentUpgradeServiceImpl service;

    @BeforeEach
    void setUp() {
        repository = Mockito.mock(ClusterAgentRepository.class);
        kubeService = Mockito.mock(KubeService.class);
        // default constructor values trigger the record's compact constructor defaults.
        properties = new AgentProperties(
                new AgentProperties.Grpc(9090, "localhost:9090", new AgentProperties.Tls(false, "", "", false)),
                new AgentProperties.Manifest(
                        "aipaas/cluster-agent:dev", "aipaas-system", "IfNotPresent", "cluster-agent", "agent"),
                new AgentProperties.Helm("anycloud", "http://chartmuseum:8080", "cluster-agent", "0.1.0"),
                new AgentProperties.ApiManaged(false),
                null);
        service = new AgentUpgradeServiceImpl(repository, kubeService, properties);
    }

    // ============================================================================
    // validation
    // ============================================================================

    @Test
    void upgradeCluster_nullClusterName_throwsInvalid() {
        assertThatThrownBy(() -> service.upgradeCluster(null, "image:v2"))
                .isInstanceOf(CustomException.class)
                .satisfies(
                        e -> assertThat(((CustomException) e).getErrorCode()).isEqualTo(ErrorCode.INVALID_INPUT_VALUE));
    }

    @Test
    void upgradeCluster_blankClusterName_throwsInvalid() {
        assertThatThrownBy(() -> service.upgradeCluster("  ", "image:v2")).isInstanceOf(CustomException.class);
    }

    @Test
    void upgradeCluster_nullTargetImage_throwsInvalid() {
        assertThatThrownBy(() -> service.upgradeCluster("orb-001", null))
                .isInstanceOf(CustomException.class)
                .satisfies(
                        e -> assertThat(((CustomException) e).getErrorCode()).isEqualTo(ErrorCode.INVALID_INPUT_VALUE));
    }

    // ============================================================================
    // cluster state
    // ============================================================================

    @Test
    void upgradeCluster_noAgentRows_throwsNotFound() {
        when(repository.findByClusterName("orb-001")).thenReturn(List.of());

        assertThatThrownBy(() -> service.upgradeCluster("orb-001", "image:v2"))
                .isInstanceOf(CustomException.class)
                .satisfies(e -> assertThat(((CustomException) e).getErrorCode()).isEqualTo(ErrorCode.ENTITY_NOT_FOUND));
    }

    @Test
    void upgradeCluster_noActiveAgent_throwsAgentNotActive() {
        // 회귀 lock — REGISTERED 만 있고 ACTIVE 없으면 upgrade 시도 차단.
        ClusterAgentEntity registered = ClusterAgentEntity.builder()
                .agentId("a1")
                .clusterName("orb-001")
                .status(ClusterAgentStatus.REGISTERED)
                .upgradeStatus(ClusterAgentUpgradeStatus.IDLE)
                .build();
        when(repository.findByClusterName("orb-001")).thenReturn(List.of(registered));

        assertThatThrownBy(() -> service.upgradeCluster("orb-001", "image:v2"))
                .isInstanceOf(CustomException.class)
                .satisfies(e -> assertThat(((CustomException) e).getErrorCode()).isEqualTo(ErrorCode.AGENT_NOT_ACTIVE));
    }

    @Test
    void upgradeCluster_upgradeInFlight_throwsConflict() {
        // 회귀 lock — IN_PROGRESS 인 row 발견되면 중복 trigger 거부 (race 방지).
        ClusterAgentEntity inFlight = ClusterAgentEntity.builder()
                .agentId("a1")
                .clusterName("orb-001")
                .status(ClusterAgentStatus.ACTIVE)
                .upgradeStatus(ClusterAgentUpgradeStatus.IN_PROGRESS)
                .upgradeTargetImage("image:v1.5")
                .build();
        when(repository.findByClusterName("orb-001")).thenReturn(List.of(inFlight));

        assertThatThrownBy(() -> service.upgradeCluster("orb-001", "image:v2"))
                .isInstanceOf(CustomException.class)
                .satisfies(
                        e -> assertThat(((CustomException) e).getErrorCode()).isEqualTo(ErrorCode.UPGRADE_IN_PROGRESS));
    }

    @Test
    void upgradeCluster_alreadyAtTarget_returnsNoOp_noApply() {
        // 회귀 lock — agent_version 이 이미 target tag 와 일치하면 no-op 으로 안전 처리.
        ClusterAgentEntity active = ClusterAgentEntity.builder()
                .agentId("a1")
                .clusterName("orb-001")
                .status(ClusterAgentStatus.ACTIVE)
                .agentVersion("v2.0")
                .upgradeStatus(ClusterAgentUpgradeStatus.IDLE)
                .build();
        when(repository.findByClusterName("orb-001")).thenReturn(List.of(active));

        UpgradeResult result = service.upgradeCluster("orb-001", "image:v2.0");

        assertThat(result.status()).isEqualTo("NO_OP");
        // no-op 이면 actual apply 호출 안 됨.
        verify(kubeService, never()).applyResource(anyString(), anyString(), anyString());
    }

    // ============================================================================
    // happy path + APPLY_MANIFEST 실패
    // ============================================================================

    @Test
    void upgradeCluster_happyPath_marksInProgress_appliesManifest() {
        ClusterAgentEntity active = ClusterAgentEntity.builder()
                .agentId("a1")
                .clusterName("orb-001")
                .status(ClusterAgentStatus.ACTIVE)
                .agentVersion("v1.0")
                .upgradeStatus(ClusterAgentUpgradeStatus.IDLE)
                .build();
        when(repository.findByClusterName("orb-001")).thenReturn(List.of(active));

        UpgradeResult result = service.upgradeCluster("orb-001", "registry/cluster-agent:v2.0");

        assertThat(result.clusterName()).isEqualTo("orb-001");
        assertThat(result.status()).isEqualTo("IN_PROGRESS");
        assertThat(active.getUpgradeStatus()).isEqualTo(ClusterAgentUpgradeStatus.IN_PROGRESS);
        assertThat(active.getUpgradeTargetImage()).isEqualTo("registry/cluster-agent:v2.0");
        assertThat(active.getUpgradeSourceVersion()).as("source snapshot").isEqualTo("v1.0");

        // manifest 가 agent namespace + deployment 로 apply 됐는지.
        ArgumentCaptor<String> manifestCaptor = ArgumentCaptor.forClass(String.class);
        verify(kubeService).applyResource(eq("orb-001"), eq("aipaas-system"), manifestCaptor.capture());
        String manifest = manifestCaptor.getValue();
        assertThat(manifest)
                .contains("name: cluster-agent")
                .contains("namespace: aipaas-system")
                .contains("- name: agent")
                .contains("image: registry/cluster-agent:v2.0");
    }

    @Test
    void upgradeCluster_applyFails_marksFailedAndThrows() {
        // 회귀 lock — APPLY_MANIFEST 실패 시 FAILED terminal state 로 즉시 전환 (운영자 재시도 가능).
        ClusterAgentEntity active = ClusterAgentEntity.builder()
                .agentId("a1")
                .clusterName("orb-001")
                .status(ClusterAgentStatus.ACTIVE)
                .agentVersion("v1.0")
                .upgradeStatus(ClusterAgentUpgradeStatus.IDLE)
                .build();
        when(repository.findByClusterName("orb-001")).thenReturn(List.of(active));
        when(kubeService.applyResource(anyString(), anyString(), anyString()))
                .thenThrow(new RuntimeException("agent stream dead"));

        assertThatThrownBy(() -> service.upgradeCluster("orb-001", "image:v2"))
                .isInstanceOf(CustomException.class)
                .satisfies(
                        e -> assertThat(((CustomException) e).getErrorCode()).isEqualTo(ErrorCode.AGENT_UNAVAILABLE));

        // FAILED + error message + completedAt 기록.
        assertThat(active.getUpgradeStatus()).isEqualTo(ClusterAgentUpgradeStatus.FAILED);
        assertThat(active.getUpgradeError()).contains("agent stream dead");
        assertThat(active.getUpgradeCompletedAt()).isNotNull();
    }

    @Test
    void upgradeCluster_imageWithoutTag_treatedAsTagItself() {
        // equalsImage helper 의 colon 없는 경우 — image 전체를 tag 로 비교.
        ClusterAgentEntity active = ClusterAgentEntity.builder()
                .agentId("a1")
                .clusterName("orb-001")
                .status(ClusterAgentStatus.ACTIVE)
                .agentVersion("notag") // tag = "notag", currentVersion = "notag" → no-op
                .upgradeStatus(ClusterAgentUpgradeStatus.IDLE)
                .build();
        when(repository.findByClusterName("orb-001")).thenReturn(List.of(active));

        UpgradeResult result = service.upgradeCluster("orb-001", "notag");

        assertThat(result.status()).isEqualTo("NO_OP");
    }
}
