package com.aipaas.anycloud.domain.vmoptions.validation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import com.aipaas.anycloud.common.error.exception.CustomException;
import com.aipaas.anycloud.domain.vmoptions.VmOptionsService;
import com.aipaas.anycloud.domain.vmoptions.api.VmOptionImage;
import com.aipaas.anycloud.domain.vmoptions.api.VmOptionSpec;
import com.aipaas.anycloud.testsupport.AbstractUnitTest;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * 인스턴스 타입과 이미지가 각각 실재해도 아키텍처가 어긋나면 조합이 불가. IBM 은 amd64
 * 프로파일에 s390x 이미지를 지정해도 두 값 모두 목록에 있어 통과했고, 인스턴스 생성 단계에서
 * "Image OS architecture s390x is not supported" 로 실패.
 */
class VmOptionsSelectionValidatorTest extends AbstractUnitTest {

    private static final String REGION = "jp-tok";

    private VmOptionsService vmOptionsService;
    private VmOptionsSelectionValidator validator;

    @BeforeEach
    void setUp() {
        vmOptionsService = mock(VmOptionsService.class);
        validator = new VmOptionsSelectionValidator(vmOptionsService);
        lenient()
                .when(vmOptionsService.isInstanceTypeAvailableInZone(
                        anyString(), any(), anyString(), any(), anyString()))
                .thenReturn(true);
    }

    private void given(String specName, String specArchitecture, String imageName, String imageArchitecture) {
        lenient()
                .when(vmOptionsService.getSpecs(
                        anyString(), any(), anyString(), any(), nullable(Boolean.class), anyInt()))
                .thenReturn(List.of(VmOptionSpec.builder()
                        .id(specName)
                        .name(specName)
                        .architecture(specArchitecture)
                        .build()));
        lenient()
                .when(vmOptionsService.getImages(anyString(), any(), anyString(), any(), any(), any(), anyInt()))
                .thenReturn(List.of(VmOptionImage.builder()
                        .id(imageName)
                        .name(imageName)
                        .architecture(imageArchitecture)
                        .build()));
    }

    private Map<String, String> config(String specName, String imageName) {
        return Map.of(
                "anycloud-k8s:masterInstanceType", specName,
                "anycloud-k8s:workerInstanceType", specName,
                "anycloud-k8s:osImage", imageName);
    }

    private void validate(String specName, String imageName) {
        validator.validateSelections("IBM", "cred", REGION, config(specName, imageName));
    }

    @Test
    void amd64Spec_withS390xImage_rejectedBeforeProvisioning() {
        given("bx2-2x8", "amd64", "ibm-redhat-9-s390x", "s390x");

        assertThatThrownBy(() -> validate("bx2-2x8", "ibm-redhat-9-s390x"))
                .isInstanceOf(CustomException.class)
                .satisfies(thrown -> {
                    CustomException e = (CustomException) thrown;
                    assertThat(e.getField()).isEqualTo("masterInstanceType");
                    assertThat(e.getReason()).contains("amd64").contains("s390x");
                });
    }

    @Test
    void matchingArchitecture_passes() {
        given("bx2-2x8", "amd64", "ibm-ubuntu-24-04-amd64", "amd64");

        assertThatCode(() -> validate("bx2-2x8", "ibm-ubuntu-24-04-amd64")).doesNotThrowAnyException();
    }

    @Test
    void aliasedArchitecture_isNotTreatedAsMismatch() {
        // 같은 아키텍처의 다른 표기. 불일치로 보면 정상 조합이 차단됨.
        given("t3.medium", "x86_64", "ami-ubuntu", "amd64");

        assertThatCode(() -> validate("t3.medium", "ami-ubuntu")).doesNotThrowAnyException();
    }

    @Test
    void unknownArchitecture_skipsCheck() {
        // 표에 없는 표기는 판정 불가. 차단 대신 Pulumi 에 위임하는 본 클래스의 기존 방침과 동일.
        given("custom-1", "sparc", "image-1", "amd64");

        assertThatCode(() -> validate("custom-1", "image-1")).doesNotThrowAnyException();
    }

    @Test
    void missingArchitecture_skipsCheck() {
        // 아키텍처를 채우지 않는 provider 에서 정상 조합이 차단되면 안 됨.
        given("flavor-1", null, "image-1", "amd64");

        assertThatCode(() -> validate("flavor-1", "image-1")).doesNotThrowAnyException();
    }

    @Test
    void emptySpecList_skipsCheck() {
        // CSP API 장애. 기존 동작 유지 확인.
        lenient()
                .when(vmOptionsService.getSpecs(
                        anyString(), any(), anyString(), any(), nullable(Boolean.class), anyInt()))
                .thenReturn(List.of());
        lenient()
                .when(vmOptionsService.getImages(anyString(), any(), anyString(), any(), any(), any(), anyInt()))
                .thenReturn(List.of(VmOptionImage.builder()
                        .id("image-1")
                        .name("image-1")
                        .architecture("s390x")
                        .build()));

        assertThatCode(() -> validate("bx2-2x8", "image-1")).doesNotThrowAnyException();
    }

    @Test
    void proxmox_returnsBeforeAnyLookup() {
        validator.validateSelections("Proxmox", "cred", REGION, config("2-4096", "ubuntu-24.04"));

        verifyNoInteractions(vmOptionsService);
    }

    @Test
    void unknownSpecName_stillRejectedAsNotFound() {
        given("bx2-2x8", "amd64", "image-1", "amd64");

        assertThatThrownBy(() -> validate("bx2-4x16", "image-1"))
                .isInstanceOf(CustomException.class)
                .hasMessageContaining("Selected VM spec was not found");
    }
}
