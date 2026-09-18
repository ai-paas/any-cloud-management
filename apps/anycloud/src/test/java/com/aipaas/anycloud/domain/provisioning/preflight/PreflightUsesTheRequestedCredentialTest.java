package com.aipaas.anycloud.domain.provisioning.preflight;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.aipaas.anycloud.domain.cluster.ClusterRepository;
import com.aipaas.anycloud.domain.credential.CspCredentialService;
import com.aipaas.anycloud.domain.credential.ResolvedCspCredential;
import com.aipaas.anycloud.domain.provisioning.VmClusterRepository;
import com.aipaas.anycloud.domain.provisioning.api.request.ProvisionClusterRequest;
import com.aipaas.anycloud.domain.provisioning.preflight.internal.VmClusterPreflightServiceImpl;
import com.aipaas.anycloud.domain.provisioning.preflight.validation.ProvisioningProviderValidator;
import com.aipaas.anycloud.domain.provisioning.properties.PulumiProperties;
import com.aipaas.anycloud.domain.vmoptions.VmOptionsQueryService;
import com.aipaas.anycloud.domain.vmoptions.api.VmOptionRegion;
import com.aipaas.anycloud.domain.vmoptions.validation.VmOptionsSelectionValidator;
import com.aipaas.anycloud.testsupport.AbstractUnitTest;
import io.aipaas.cluster.provisioning.api.ProvisioningService;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

/**
 * 사전 검토가 요청에 담긴 자격증명으로 조회해야 한다.
 *
 * <p>버리면 조회가 환경변수 fallback 으로 떨어져 "조회 불가"가 되고, 등록한 자격증명으로 만들려던
 * 요청이 {@code readyToProvision=false} 로 막힌다. 화면에는 자격증명이 정상이라고 나오는데 진행은
 * 안 되는 상태라 원인을 찾기 어렵다.
 */
@MockitoSettings(strictness = Strictness.LENIENT)
class PreflightUsesTheRequestedCredentialTest extends AbstractUnitTest {

    private static final String CREDENTIAL_ID = "cred-1";

    @Mock
    ClusterRepository clusterRepository;

    @Mock
    VmClusterRepository vmClusterRepository;

    @Mock
    CspCredentialService cspCredentialService;

    @Mock
    ProvisioningService provisioningService;

    @Mock
    VmOptionsQueryService vmOptionsQueryService;

    @Mock
    VmOptionsSelectionValidator vmOptionsSelectionValidator;

    @Mock
    ProvisioningProviderValidator provisioningProviderValidator;

    private ProvisionClusterRequest request() {
        ProvisionClusterRequest request = new ProvisionClusterRequest();
        request.setClusterProvider("IBM");
        request.setClusterName("ibm-e2e-01");
        request.setEnvironment("dev");
        request.setRegion("us-south");
        request.setCredentialId(CREDENTIAL_ID);
        request.setConfig(Map.of(
                "anycloud-k8s:masterCount", "1",
                "anycloud-k8s:workerCount", "1",
                "anycloud-k8s:providerSpec.zone", "us-south-1"));
        return request;
    }

    private VmClusterPreflightServiceImpl service() {
        return new VmClusterPreflightServiceImpl(
                clusterRepository,
                vmClusterRepository,
                cspCredentialService,
                provisioningService,
                vmOptionsQueryService,
                vmOptionsSelectionValidator,
                new PulumiProperties(),
                provisioningProviderValidator);
    }

    @Test
    void regionDiscoveryIsCalledWithTheCredentialFromTheRequest() {
        // 조회 단계는 자격증명이 다 채워졌을 때만 돈다.
        when(cspCredentialService.resolveForProvision(anyString(), anyString()))
                .thenReturn(ResolvedCspCredential.builder()
                        .credentialId(CREDENTIAL_ID)
                        .credentialName("ibm-e2e-01")
                        .environment(Map.of("IBMCLOUD_API_KEY", "x"))
                        .build());
        when(vmOptionsQueryService.listRegions(anyString(), any()))
                .thenReturn(List.of(VmOptionRegion.builder()
                        .provider("IBM")
                        .id("us-south")
                        .name("us-south")
                        .available(true)
                        .build()));

        service().preflightVmCluster(request());

        ArgumentCaptor<String> credential = ArgumentCaptor.forClass(String.class);
        verify(vmOptionsQueryService).listRegions(anyString(), credential.capture());
        assertThat(credential.getValue())
                .as("요청의 credentialId 를 버리고 조회하면 환경변수 fallback 으로 떨어진다")
                .isEqualTo(CREDENTIAL_ID);
    }
}
