package com.aipaas.anycloud.domain.provisioning.workflow.support.internal;

import static org.assertj.core.api.Assertions.assertThat;

import com.aipaas.anycloud.domain.credential.CspCredentialService;
import com.aipaas.anycloud.domain.operation.OperationService;
import com.aipaas.anycloud.domain.provisioning.VmClusterEntity;
import com.aipaas.anycloud.domain.provisioning.VmClusterRepository;
import com.aipaas.anycloud.domain.provisioning.bootstrap.VmClusterBootstrapLogService;
import com.aipaas.anycloud.domain.provisioning.model.VmClusterStatus;
import com.aipaas.anycloud.domain.provisioning.payload.VmClusterPayloadService;
import com.aipaas.anycloud.domain.provisioning.properties.VmClusterWorkflowProperties;
import com.aipaas.anycloud.domain.provisioning.workflow.VmClusterWorkflowPublisher;
import com.aipaas.anycloud.domain.webhook.WebhookEventPublisher;
import com.aipaas.anycloud.testsupport.AbstractUnitTest;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

/**
 * DESTROY 완료 처리 회귀 보호.
 *
 * <p>DESTROY 는 cluster row 를 지운 뒤 vm_cluster 를 저장한다. clusterId 를 그대로 두면 flush 가
 * 사라진 FK 를 다시 써서 1452 로 죽고, vm_cluster 가 DELETING 에 갇혀 같은 이름 재생성이 409 가 된다.
 */
class VmClusterWorkflowSupportServiceImplTest extends AbstractUnitTest {

    private final VmClusterRepository vmClusterRepository = Mockito.mock(VmClusterRepository.class);

    private final VmClusterWorkflowSupportServiceImpl support = new VmClusterWorkflowSupportServiceImpl(
            vmClusterRepository,
            Mockito.mock(CspCredentialService.class),
            Mockito.mock(io.aipaas.cluster.provisioning.api.ProvisioningService.class),
            Mockito.mock(VmClusterPayloadService.class),
            Mockito.mock(VmClusterBootstrapLogService.class),
            Mockito.mock(VmClusterWorkflowProperties.class),
            Mockito.mock(VmClusterWorkflowPublisher.class),
            Mockito.mock(WebhookEventPublisher.class),
            Mockito.mock(OperationService.class));

    private VmClusterEntity registeredCluster() {
        VmClusterEntity vm = new VmClusterEntity();
        vm.setClusterName("demo");
        vm.setClusterId("demo");
        vm.setClusterRegistered(true);
        vm.setProvisioningStatus(VmClusterStatus.DELETING);
        return vm;
    }

    private VmClusterEntity saved() {
        ArgumentCaptor<VmClusterEntity> captor = ArgumentCaptor.forClass(VmClusterEntity.class);
        Mockito.verify(vmClusterRepository).save(captor.capture());
        return captor.getValue();
    }

    @Test
    void markDeleteCompleted_clearsClusterIdSoFlushCannotResurrectDeletedFk() {
        support.markDeleteCompleted(registeredCluster());

        assertThat(saved().getClusterId()).isNull();
    }

    @Test
    void markDeleteCompleted_marksDeletedAndUnregistered() {
        support.markDeleteCompleted(registeredCluster());

        VmClusterEntity result = saved();
        assertThat(result.getProvisioningStatus()).isEqualTo(VmClusterStatus.DELETED);
        assertThat(result.getClusterRegistered()).isFalse();
        assertThat(result.getDeletedAt()).isNotNull();
    }

    @Test
    void markDeleteCompleted_wipesSensitivePayloadButKeepsAuditMetadata() {
        VmClusterEntity vm = registeredCluster();
        vm.setRequestConfig("{\"OS_PASSWORD\":\"secret\"}");
        vm.setRawOutputs("{\"sshPrivateKeyPem\":\"-----BEGIN\"}");
        vm.setBootstrapLog("log");

        support.markDeleteCompleted(vm);

        VmClusterEntity result = saved();
        assertThat(result.getRequestConfig()).isNull();
        assertThat(result.getRawOutputs()).isNull();
        assertThat(result.getBootstrapLog()).isNull();
        assertThat(result.getClusterName()).isEqualTo("demo");
    }
}
