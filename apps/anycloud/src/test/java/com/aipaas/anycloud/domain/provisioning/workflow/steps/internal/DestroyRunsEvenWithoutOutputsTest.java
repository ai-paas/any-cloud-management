package com.aipaas.anycloud.domain.provisioning.workflow.steps.internal;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

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
 * 만들다 만 스택도 반드시 destroy 를 돌려야 한다.
 *
 * <p>Pulumi {@code up} 이 중간에 실패하면 보안 그룹과 네트워크는 만들어졌는데 스택 출력값은 비어
 * 있다. 그 숫자를 "남은 자원 수"로 읽고 destroy 를 건너뛰는 바람에, 동시 프로비저닝 리허설에서
 * 실패한 6개의 보안 그룹이 공유 테넌트에 그대로 남아 쿼터를 물었다.
 *
 * <p>destroy 는 멱등이다. 없으면 금방 끝나고, 있으면 지운다.
 */
@MockitoSettings(strictness = Strictness.LENIENT)
class DestroyRunsEvenWithoutOutputsTest extends AbstractUnitTest {

    @Mock
    ClusterRepository clusterRepository;

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
        // up 이 일찍 실패해 출력값이 없다 — 그래도 자원은 남아 있을 수 있다.
        when(provisioningService.stackOutputs(eq(STACK), anyBoolean(), any())).thenReturn(Map.of());

        service.execute("rehearsal-02");

        verify(provisioningService).destroy(eq(STACK), any());
    }

    @Test
    void aStackWeCannotInspectIsStillDestroyed() {
        // CSP 가 응답하지 않는다고 정리를 포기하면 치울 방법이 없어진다.
        when(provisioningService.stackOutputs(eq(STACK), anyBoolean(), any()))
                .thenThrow(new IllegalStateException("keystone timeout"));

        service.execute("rehearsal-02");

        verify(provisioningService).destroy(eq(STACK), any());
    }

    @Test
    void aHealthyStackIsDestroyedToo() {
        when(provisioningService.stackOutputs(eq(STACK), anyBoolean(), any()))
                .thenReturn(Map.of("masterPublicIp", "1.2.3.4"));

        service.execute("rehearsal-02");

        verify(provisioningService).destroy(eq(STACK), any());
    }
}
