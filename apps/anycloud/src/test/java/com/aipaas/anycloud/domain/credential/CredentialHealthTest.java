package com.aipaas.anycloud.domain.credential;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import com.aipaas.anycloud.common.error.enums.ErrorCode;
import com.aipaas.anycloud.common.error.exception.CustomException;
import com.aipaas.anycloud.domain.credential.internal.CredentialHealthServiceImpl;
import com.aipaas.anycloud.domain.vmoptions.VmOptionsService;
import com.aipaas.anycloud.domain.vmoptions.api.VmOptionRegion;
import com.aipaas.anycloud.testsupport.AbstractUnitTest;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;

/**
 * 자격증명 유효성 확인.
 *
 * <p>등록만으로는 사용 가능 여부 확인 불가. 리전 조회는 CSP API 를 실제 호출하므로 그 자체가
 * 검증이며 노출면 증가 없이 확인 가능.
 */
class CredentialHealthTest extends AbstractUnitTest {

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
                vmOptionsService,
                credentialRepository,
                credentialService,
                proxmoxApiClient,
                java.time.Duration.ofMinutes(10));
    }

    @Test
    void healthyWhenTheCspAnswers() {
        when(vmOptionsService.getRegions(anyString(), anyString()))
                .thenReturn(List.of(VmOptionRegion.builder().id("ap-tokyo-1").build()));

        var result = service.check("OCI", "cred-1");

        assertThat(result.healthy()).isTrue();
        assertThat(result.checkedRegions()).isEqualTo(1);
    }

    @Test
    void unhealthyWhenTheCspRejects() {
        when(vmOptionsService.getRegions(anyString(), anyString()))
                .thenThrow(
                        new CustomException(ErrorCode.RUNTIME_EXCEPTION, "oci", "url", "404 NotAuthorizedOrNotFound"));

        var result = service.check("OCI", "cred-1");

        assertThat(result.healthy()).isFalse();
        // 조치 대상까지 안내해야 확인 기능이 유효.
        assertThat(result.kind()).isEqualTo("PERMISSION_DENIED");
        assertThat(result.hint()).contains("권한");
    }

    @Test
    void emptyRegionListIsNotHealthy() {
        // 호출은 성공하나 조회 결과가 없는 자격증명은 사용 불가.
        when(vmOptionsService.getRegions(anyString(), anyString())).thenReturn(List.of());

        var result = service.check("AWS", "cred-1");

        assertThat(result.healthy()).isFalse();
    }

    @Test
    void keepsTheRawMessageForSupport() {
        when(vmOptionsService.getRegions(anyString(), anyString()))
                .thenThrow(new IllegalStateException("boom from csp"));

        assertThat(service.check("AWS", "cred-1").detail()).contains("boom from csp");
    }
}
