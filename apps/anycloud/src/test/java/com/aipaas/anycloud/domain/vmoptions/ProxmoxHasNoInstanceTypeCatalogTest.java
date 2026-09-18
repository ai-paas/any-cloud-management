package com.aipaas.anycloud.domain.vmoptions;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.verifyNoInteractions;

import com.aipaas.anycloud.domain.vmoptions.validation.VmOptionsSelectionValidator;
import com.aipaas.anycloud.testsupport.AbstractUnitTest;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;

/**
 * Proxmox 요청이 인스턴스 타입 목록 조회로 새지 않아야 한다.
 *
 * <p>Proxmox 는 인스턴스 타입이 없어 {@code VmOptionsProvider} 구현이 없다. 조회로 내려가면
 * {@code VM options provider is not registered} 로 PROVISION 이 5% 에서 끝난다.
 */
class ProxmoxHasNoInstanceTypeCatalogTest extends AbstractUnitTest {

    @Mock
    VmOptionsQueryService vmOptionsQueryService;

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
