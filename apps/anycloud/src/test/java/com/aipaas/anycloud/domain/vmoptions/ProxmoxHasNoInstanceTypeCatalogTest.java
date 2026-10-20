package com.aipaas.anycloud.domain.vmoptions;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.verifyNoInteractions;

import com.aipaas.anycloud.domain.vmoptions.validation.VmOptionsSelectionValidator;
import com.aipaas.anycloud.testsupport.AbstractUnitTest;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;

/**
 * Proxmox 요청의 인스턴스 타입 목록 조회 경로 유입 차단.
 *
 * <p>Proxmox 는 인스턴스 타입이 없어 {@code VmOptionsProvider} 구현 부재. 조회 경로 진입 시
 * {@code VM options provider is not registered} 로 PROVISION 이 5% 에서 종료.
 */
class ProxmoxHasNoInstanceTypeCatalogTest extends AbstractUnitTest {

    @Mock
    VmOptionsService vmOptionsQueryService;

    private Map<String, String> config() {
        return Map.of(
                "anycloud-k8s:masterInstanceType", "2-4096",
                "anycloud-k8s:workerInstanceType", "2-4096");
    }

    @Test
    void proxmoxNeverQueriesTheSpecCatalog() {
        VmOptionsSelectionValidator validator = new VmOptionsSelectionValidator(vmOptionsQueryService);

        assertThatCode(() -> validator.validateSelections("Proxmox", "cred-1", "proxmox", config()))
                .doesNotThrowAnyException();

        verifyNoInteractions(vmOptionsQueryService);
    }
}
