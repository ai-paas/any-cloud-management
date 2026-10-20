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
 * Proxmox 는 리전 개념이 없어 리전 조회로 자격증명 검증 불가.
 *
 * <p>미조치 시 등록된 VM options provider 부재로 예외 종료되고 유효한 토큰도 확인 불가로 표시.
 * 사용자가 연결 문제로 오인해 자격증명을 재생성.
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
        // 이 경로 진입 시 "VM options provider is not registered" 로 종료.
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
        // 401 은 토큰 오류 또는 만료. 사용자 조치가 가능한 원인이므로 그대로 통보.
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
