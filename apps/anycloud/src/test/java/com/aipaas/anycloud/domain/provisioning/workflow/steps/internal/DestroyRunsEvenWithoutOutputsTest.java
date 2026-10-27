package com.aipaas.anycloud.domain.provisioning.workflow.steps.internal;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.aipaas.anycloud.domain.agent.ClusterAgentRepository;
import com.aipaas.anycloud.domain.cluster.ClusterRepository;
import com.aipaas.anycloud.domain.credential.CspCredentialService;
import com.aipaas.anycloud.domain.provisioning.VmClusterEntity;
import com.aipaas.anycloud.domain.provisioning.VmClusterRepository;
import com.aipaas.anycloud.domain.provisioning.model.VmClusterStatus;
import com.aipaas.anycloud.domain.provisioning.workflow.support.VmClusterWorkflowSupportService;
import com.aipaas.anycloud.testsupport.AbstractUnitTest;
import io.aipaas.cluster.provisioning.api.ProvisioningService;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * 생성 중단 스택도 destroy 실행 필요.
 *
 * <p>Pulumi {@code up} 이 중간에 실패하면 보안 그룹과 네트워크는 생성된 상태에서 스택 출력값은
 * 빈 값. 해당 숫자를 잔여 자원 수로 해석해 destroy 를 생략한 결과, 동시 프로비저닝 리허설에서
 * 실패한 보안 그룹 6개가 공유 테넌트에 잔존해 쿼터 소모.
 *
 * <p>destroy 는 멱등. 대상이 없으면 즉시 종료, 있으면 삭제.
 */
@MockitoSettings(strictness = Strictness.LENIENT)
class DestroyRunsEvenWithoutOutputsTest extends AbstractUnitTest {

    @Mock
    ClusterRepository clusterRepository;

    @Mock
    ClusterAgentRepository clusterAgentRepository;

    @Mock
    VmClusterRepository vmClusterRepository;

    @Mock
    CspCredentialService cspCredentialService;

    @Mock
    ProvisioningService provisioningService;

    @Mock
    VmClusterWorkflowSupportService workflowSupportService;

    private VmClusterDestroyStepServiceImpl service;

    private static final String STACK = "anycloud-OpenStack-dev-rehearsal-02";

    @BeforeEach
    void setUp() {
        service = new VmClusterDestroyStepServiceImpl(
                clusterRepository,
                clusterAgentRepository,
                vmClusterRepository,
                cspCredentialService,
                provisioningService,
                workflowSupportService);
        ReflectionTestUtils.setField(service, "precheckEnabled", true);

        VmClusterEntity cluster = VmClusterEntity.builder()
                .id("c1")
                .clusterName("rehearsal-02")
                .clusterProvider("OpenStack")
                .credentialId("cred-1")
                .stackName(STACK)
                .provisioningStatus(VmClusterStatus.DELETING)
                .build();

        when(vmClusterRepository.findAllByClusterNameOrderByCreatedAtDesc(anyString()))
                .thenReturn(List.of(cluster));
        when(workflowSupportService.getLatestVmCluster(anyString())).thenReturn(cluster);
        when(cspCredentialService.resolveEnvironment(anyString(), anyString())).thenReturn(Map.of());
        when(clusterRepository.findById(anyString())).thenReturn(Optional.empty());
    }

    @Test
    void aFailedStackWithNoOutputsIsStillDestroyed() {
        // up 조기 실패로 출력값 부재. 자원은 잔존 가능.
        when(provisioningService.stackOutputs(eq(STACK), anyBoolean(), any())).thenReturn(Map.of());

        service.execute("rehearsal-02");

        verify(provisioningService).destroy(eq(STACK), any());
    }

    @Test
    void aStackWeCannotInspectIsStillDestroyed() {
        // CSP 무응답을 이유로 정리를 중단하면 제거 수단 부재.
        when(provisioningService.stackOutputs(eq(STACK), anyBoolean(), any()))
                .thenThrow(new IllegalStateException("keystone timeout"));

        service.execute("rehearsal-02");

        verify(provisioningService).destroy(eq(STACK), any());
    }

    @Test
    void agentRowsAreRemovedSoAReusedNameDoesNotInheritADeadAgent() {
        /*
         * ClusterServiceImpl.deleteCluster 가 같은 정리를 하지만 VM 기반 클러스터를 거부해
         * 이 경로에서는 도달하지 않았다. 남은 행은 같은 이름으로 재생성할 때 cluster_name 으로
         * 살아남아, 새 agent 가 하트비트를 시작하기 전까지 primary 로 뽑혀 DEGRADED 로 보고됐다.
         */
        when(provisioningService.stackOutputs(eq(STACK), anyBoolean(), any())).thenReturn(Map.of());

        service.execute("rehearsal-02");

        verify(clusterAgentRepository).deleteByClusterName("rehearsal-02");
    }

    @Test
    void aFailedAgentCleanupDoesNotUndoASuccessfulDestroy() {
        /*
         * 장부 정리는 CSP 자원과 cluster 등록을 지운 뒤에 온다. 여기서 예외를 올리면 실제로는
         * 삭제가 끝났는데 단계가 FAILED 로 기록돼 상태가 어긋나고 재시도도 할 수 없다.
         */
        when(provisioningService.stackOutputs(eq(STACK), anyBoolean(), any())).thenReturn(Map.of());
        doThrow(new IllegalStateException("No EntityManager with actual transaction available"))
                .when(clusterAgentRepository)
                .deleteByClusterName(anyString());

        service.execute("rehearsal-02");

        verify(provisioningService).destroy(eq(STACK), any());
        verify(workflowSupportService).markDeleteCompleted(any());
    }

    @Test
    void aHealthyStackIsDestroyedToo() {
        when(provisioningService.stackOutputs(eq(STACK), anyBoolean(), any()))
                .thenReturn(Map.of("masterPublicIp", "1.2.3.4"));

        service.execute("rehearsal-02");

        verify(provisioningService).destroy(eq(STACK), any());
    }
}
