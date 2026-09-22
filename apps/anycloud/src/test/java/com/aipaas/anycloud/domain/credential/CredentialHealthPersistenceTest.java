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
 * 확인 결과를 남긴다.
 *
 * <p>화면 상태로만 두면 새로고침하면 사라지고 사용자마다 각자 확인해야 한다. 반대로 페이지를 열
 * 때마다 전부 다시 확인하면 CSP 요청 제한에 걸린다 — 오래된 것만 다시 본다.
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
        // 페이지를 열 때마다 전부 다시 확인하면 CSP 요청 제한에 걸린다.
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
        // 사용자가 직접 누른 확인은 캐시를 건너뛴다. 방금 키를 고쳤을 수 있다.
        CspCredentialEntity fresh = entity();
        fresh.setHealthCheckedAt(LocalDateTime.now());
        when(credentialRepository.findById("cred-1")).thenReturn(Optional.of(fresh));
        when(vmOptionsService.getRegions(anyString(), anyString()))
                .thenReturn(List.of(VmOptionRegion.builder().id("r").build()));

        service.check("OCI", "cred-1");

        verify(vmOptionsService).getRegions("OCI", "cred-1");
    }
}
