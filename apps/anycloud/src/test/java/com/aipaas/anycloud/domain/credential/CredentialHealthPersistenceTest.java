package com.aipaas.anycloud.domain.credential;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.aipaas.anycloud.common.error.enums.ErrorCode;
import com.aipaas.anycloud.common.error.exception.CustomException;
import com.aipaas.anycloud.domain.credential.internal.CredentialHealthServiceImpl;
import com.aipaas.anycloud.domain.vmoptions.VmOptionsService;
import com.aipaas.anycloud.domain.vmoptions.api.VmOptionRegion;
import com.aipaas.anycloud.testsupport.AbstractUnitTest;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;

/**
 * 확인 결과 영속화.
 *
 * <p>화면 상태로만 유지하면 새로고침 시 소실되고 사용자별 재확인 필요. 페이지 진입마다 전체
 * 재확인하면 CSP 요청 제한에 도달. 오래된 항목만 재확인.
 */
class CredentialHealthPersistenceTest extends AbstractUnitTest {

    @Mock
    VmOptionsService vmOptionsService;

    @Mock
    com.aipaas.anycloud.domain.credential.CspCredentialService credentialService;

    @Mock
    com.aipaas.anycloud.domain.provisioning.proxmox.ProxmoxApiClient proxmoxApiClient;

    @Mock
    CspCredentialRepository credentialRepository;

    private CredentialHealthServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new CredentialHealthServiceImpl(
                vmOptionsService, credentialRepository, credentialService, proxmoxApiClient, Duration.ofMinutes(10));
    }

    private CspCredentialEntity entity() {
        CspCredentialEntity e = new CspCredentialEntity();
        e.setId("cred-1");
        e.setProvider("OCI");
        e.setName("oci-1");
        return e;
    }

    @Test
    void storesTheResult() {
        CspCredentialEntity e = entity();
        when(credentialRepository.findById("cred-1")).thenReturn(Optional.of(e));
        when(vmOptionsService.getRegions(anyString(), anyString()))
                .thenReturn(List.of(VmOptionRegion.builder().id("ap-tokyo-1").build()));

        service.check("OCI", "cred-1");

        assertThat(e.getHealthStatus()).isEqualTo("HEALTHY");
        assertThat(e.getHealthCheckedAt()).isNotNull();
        verify(credentialRepository).save(e);
    }

    @Test
    void storesTheFailureReason() {
        CspCredentialEntity e = entity();
        when(credentialRepository.findById("cred-1")).thenReturn(Optional.of(e));
        when(vmOptionsService.getRegions(anyString(), anyString()))
                .thenThrow(new CustomException(ErrorCode.RUNTIME_EXCEPTION, "oci", "u", "404 NotAuthorizedOrNotFound"));

        service.check("OCI", "cred-1");

        assertThat(e.getHealthStatus()).isEqualTo("UNHEALTHY");
        assertThat(e.getHealthKind()).isEqualTo("PERMISSION_DENIED");
        assertThat(e.getHealthDetail()).contains("NotAuthorized");
    }

    @Test
    void refreshSkipsRecentlyCheckedCredentials() {
        // 페이지 진입마다 전체 재확인 시 CSP 요청 제한 도달.
        CspCredentialEntity fresh = entity();
        fresh.setHealthCheckedAt(LocalDateTime.now().minusMinutes(1));
        when(credentialRepository.findById("cred-1")).thenReturn(Optional.of(fresh));

        service.refreshIfStale("OCI", "cred-1");

        verify(vmOptionsService, never()).getRegions(anyString(), anyString());
    }

    @Test
    void refreshChecksStaleCredentials() {
        CspCredentialEntity stale = entity();
        stale.setHealthCheckedAt(LocalDateTime.now().minusHours(1));
        when(credentialRepository.findById("cred-1")).thenReturn(Optional.of(stale));
        when(vmOptionsService.getRegions(anyString(), anyString()))
                .thenReturn(List.of(VmOptionRegion.builder().id("r").build()));

        service.refreshIfStale("OCI", "cred-1");

        verify(vmOptionsService).getRegions("OCI", "cred-1");
    }

    @Test
    void refreshChecksCredentialsNeverCheckedBefore() {
        when(credentialRepository.findById("cred-1")).thenReturn(Optional.of(entity()));
        when(vmOptionsService.getRegions(anyString(), anyString()))
                .thenReturn(List.of(VmOptionRegion.builder().id("r").build()));

        service.refreshIfStale("OCI", "cred-1");

        verify(vmOptionsService).getRegions("OCI", "cred-1");
    }

    @Test
    void checkAlwaysRunsEvenWhenFresh() {
        // 사용자가 직접 실행한 확인은 캐시 우회. 직전 키 수정 가능성.
        CspCredentialEntity fresh = entity();
        fresh.setHealthCheckedAt(LocalDateTime.now());
        when(credentialRepository.findById("cred-1")).thenReturn(Optional.of(fresh));
        when(vmOptionsService.getRegions(anyString(), anyString()))
                .thenReturn(List.of(VmOptionRegion.builder().id("r").build()));

        service.check("OCI", "cred-1");

        verify(vmOptionsService).getRegions("OCI", "cred-1");
    }
}
