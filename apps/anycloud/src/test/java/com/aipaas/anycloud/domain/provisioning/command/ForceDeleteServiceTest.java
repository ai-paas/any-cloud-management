package com.aipaas.anycloud.domain.provisioning.command;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.aipaas.anycloud.common.error.exception.ClusterNotFoundException;
import com.aipaas.anycloud.domain.cluster.ClusterRepository;
import com.aipaas.anycloud.domain.credential.CspCredentialService;
import com.aipaas.anycloud.domain.provisioning.VmClusterEntity;
import com.aipaas.anycloud.domain.provisioning.VmClusterRepository;
import com.aipaas.anycloud.domain.provisioning.command.internal.VmClusterCommandServiceImpl;
import com.aipaas.anycloud.domain.provisioning.model.VmClusterStatus;
import com.aipaas.anycloud.domain.provisioning.payload.VmClusterPayloadService;
import com.aipaas.anycloud.domain.provisioning.preflight.validation.ProvisioningProviderValidator;
import com.aipaas.anycloud.domain.provisioning.registration.VmClusterRegistrationService;
import com.aipaas.anycloud.domain.provisioning.workflow.VmClusterAsyncService;
import com.aipaas.anycloud.domain.provisioning.workflow.VmClusterWorkflowPublisher;
import com.aipaas.anycloud.testsupport.AbstractUnitTest;
import io.aipaas.cluster.provisioning.api.ProvisioningService;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.InjectMocks;
import org.mockito.Mock;

/**
 * 강제 삭제는 마지막 수단이다. 무엇을 지웠고 무엇이 남는지 정확히 보고하지 않으면
 * 사용자는 CSP 에 남은 자원을 모른 채 요금을 계속 낸다.
 */
class ForceDeleteServiceTest extends AbstractUnitTest {

    @Mock
    private ClusterRepository clusterRepository;

    @Mock
    private VmClusterRepository vmClusterRepository;

    @Mock
    private VmClusterAsyncService vmClusterAsyncService;

    @Mock
    private VmClusterPayloadService vmClusterPayloadService;

    @Mock
    private ProvisioningService provisioningService;

    @Mock
    private ProvisioningProviderValidator provisioningProviderValidator;

    @Mock
    private VmClusterRegistrationService vmClusterRegistrationService;

    @Mock
    private CspCredentialService cspCredentialService;

    @Mock
    private VmClusterWorkflowPublisher workflowPublisher;

    @InjectMocks
    private VmClusterCommandServiceImpl service;

    private VmClusterEntity row(String id, VmClusterStatus status, String stackName) {
        VmClusterEntity e = new VmClusterEntity();
        e.setId(id);
        e.setClusterName("demo");
        e.setProvisioningStatus(status);
        e.setStackName(stackName);
        e.setCreatedAt(LocalDateTime.now());
        return e;
    }

    @Test
    void countsEveryGenerationItRemoved() {
        List<VmClusterEntity> generations = List.of(
                row("a", VmClusterStatus.DELETING, "stack-1"),
                row("b", VmClusterStatus.FAILED, "stack-1"),
                row("c", VmClusterStatus.FAILED, null));
        when(vmClusterRepository.findAllByClusterNameOrderByCreatedAtDesc("demo"))
                .thenReturn(generations);
        when(clusterRepository.findById("demo")).thenReturn(Optional.empty());

        ForceDeleteResult result = service.forceDeleteVmCluster("demo");

        // 남을 수 있는 스택 수(1)가 아니라 지운 행 수(3)다
        assertThat(result.removedRecords()).isEqualTo(3);
        assertThat(result.orphanedStacks()).containsExactly("stack-1");
        verify(vmClusterRepository).deleteAll(generations);
    }

    @Test
    void neverTriggersDestroy() {
        when(vmClusterRepository.findAllByClusterNameOrderByCreatedAtDesc("demo"))
                .thenReturn(List.of(row("a", VmClusterStatus.FAILED, "stack-1")));
        when(clusterRepository.findById("demo")).thenReturn(Optional.empty());

        service.forceDeleteVmCluster("demo");

        verify(vmClusterAsyncService, never()).destroyClusterAsync(any());
    }

    @Test
    void rejectsUnknownCluster() {
        when(vmClusterRepository.findAllByClusterNameOrderByCreatedAtDesc("nope"))
                .thenReturn(List.of());

        assertThatThrownBy(() -> service.forceDeleteVmCluster("nope")).isInstanceOf(ClusterNotFoundException.class);
        verify(vmClusterRepository, never()).deleteAll(any());
    }
}
