package com.aipaas.anycloud.domain.credential;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.aipaas.anycloud.domain.credential.internal.CredentialHealthServiceImpl;
import com.aipaas.anycloud.domain.provisioning.proxmox.ProxmoxApiClient;
import com.aipaas.anycloud.domain.vmoptions.VmOptionsService;
import com.aipaas.anycloud.testsupport.AbstractUnitTest;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

/**
 * Proxmox 는 리전이 없어 리전 조회로 자격증명을 확인할 수 없다.
 *
 * <p>그대로 두면 등록된 VM options provider 가 없어 예외로 끝나고, 멀쩡한 토큰도 "확인 불가" 로
 * 보인다. 사용자는 연결 문제로 오해하고 자격증명을 다시 만든다.
 */
@MockitoSettings(strictness = Strictness.LENIENT)
class ProxmoxCredentialHealthTest extends AbstractUnitTest {

    private static final String CREDENTIAL_ID = "cred-pve";

    @Mock
    VmOptionsService vmOptionsService;

    @Mock
    CspCredentialRepository credentialRepository;

    @Mock
    CspCredentialService credentialService;

    @Mock
    ProxmoxApiClient proxmoxApiClient;

    private final ObjectMapper mapper = new ObjectMapper();

    private CredentialHealthServiceImpl service() {
        when(credentialRepository.findById(anyString())).thenReturn(Optional.empty());
        when(credentialService.resolveForProvision(anyString(), anyString()))
                .thenReturn(ResolvedCspCredential.builder()
                        .credentialId(CREDENTIAL_ID)
                        .environment(Map.of("PROXMOX_VE_ENDPOINT", "https://pve:8006"))
                        .build());
        return new CredentialHealthServiceImpl(
                vmOptionsService, credentialRepository, credentialService, proxmoxApiClient, Duration.ofMinutes(10));
    }

    @Test
    void aWorkingTokenIsReportedHealthy() throws Exception {
        when(proxmoxApiClient.version(any())).thenReturn(mapper.readTree("{\"version\":\"9.2.10\"}"));

        assertThat(service().check("Proxmox", CREDENTIAL_ID).healthy()).isTrue();
    }

    @Test
    void theRegionCatalogIsNeverConsultedForProxmox() {
        // 여기로 내려가면 "VM options provider is not registered" 로 끝난다.
        when(proxmoxApiClient.version(any())).thenReturn(null);

        service().check("Proxmox", CREDENTIAL_ID);

        verify(vmOptionsService, never()).getRegions(anyString(), anyString());
    }

    @Test
    void anUnreachableHostIsReportedAsUpstreamProblem() {
        when(proxmoxApiClient.version(any())).thenReturn(null);

        CredentialHealth health = service().check("Proxmox", CREDENTIAL_ID);

        assertThat(health.healthy()).isFalse();
        assertThat(health.kind()).isEqualTo("UPSTREAM_UNAVAILABLE");
    }

    @Test
    void aRejectedTokenIsReportedAsAuthenticationFailure() {
        // 401 은 토큰이 틀렸거나 만료된 것이다. 사용자가 고칠 수 있는 원인이라 그대로 알린다.
        when(proxmoxApiClient.version(any()))
                .thenThrow(new com.aipaas.anycloud.common.error.exception.CustomException(
                        "Proxmox API 인증에 실패했습니다. 토큰 ID, 시크릿, 만료일을 확인합니다.",
                        com.aipaas.anycloud.common.error.enums.ErrorCode.INVALID_INPUT_VALUE));

        CredentialHealth health = service().check("Proxmox", CREDENTIAL_ID);

        assertThat(health.healthy()).isFalse();
        assertThat(health.detail()).contains("인증");
    }

    @Test
    void otherProvidersStillGoThroughTheRegionCatalog() {
        when(vmOptionsService.getRegions(anyString(), anyString())).thenReturn(java.util.List.of());

        service().check("AWS", "cred-aws");

        verify(vmOptionsService).getRegions("AWS", "cred-aws");
        verify(proxmoxApiClient, never()).version(any());
    }
}
