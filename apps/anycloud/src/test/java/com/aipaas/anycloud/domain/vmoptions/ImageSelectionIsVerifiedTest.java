package com.aipaas.anycloud.domain.vmoptions;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import com.aipaas.anycloud.domain.vmoptions.api.VmOptionImage;
import com.aipaas.anycloud.domain.vmoptions.validation.VmOptionsSelectionValidator;
import com.aipaas.anycloud.testsupport.AbstractUnitTest;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

/**
 * 없는 이미지를 그대로 보내면 CSP 마다 다른 방식으로 죽는다.
 *
 * <p>IBM 은 pulumi-yaml 이 패닉해 Go 스택 수백 줄이 {@code lastError} 에 담긴다. 사용자는 그
 * 안에서 무엇이 문제인지 알 길이 없다 — 만들기 전에 막아야 한다.
 */
@MockitoSettings(strictness = Strictness.LENIENT)
class ImageSelectionIsVerifiedTest extends AbstractUnitTest {

    private static final String IMAGE_KEY = "anycloud-k8s:osImage";

    @Mock
    VmOptionsService vmOptionsService;

    private VmOptionsSelectionValidator validator;

    private static VmOptionImage image(String name) {
        return VmOptionImage.builder().id(name).name(name).build();
    }

    @BeforeEach
    void setUp() {
        validator = new VmOptionsSelectionValidator(vmOptionsService);
    }

    private void validate(String value) {
        validator.validateSelections("IBM", "cred-1", "jp-tok", Map.of(IMAGE_KEY, value));
    }

    @Test
    void anImageTheAccountCannotSeeIsRejected() {
        // 키워드로는 없고 전체 목록은 나온다 — 그 값이 없는 것이다.
        when(vmOptionsService.getImages(anyString(), anyString(), anyString(), eq("ghost"), any(), any(), anyInt()))
                .thenReturn(List.of());
        when(vmOptionsService.getImages(anyString(), anyString(), anyString(), eq(null), any(), any(), anyInt()))
                .thenReturn(List.of(image("ibm-ubuntu-24-04-4-minimal-amd64-7")));

        assertThatThrownBy(() -> validate("ghost")).hasMessageContaining("OS image");
    }

    @Test
    void anImageTheAccountHasPasses() {
        when(vmOptionsService.getImages(anyString(), anyString(), anyString(), anyString(), any(), any(), anyInt()))
                .thenReturn(List.of(image("ibm-ubuntu-24-04-4-minimal-amd64-7")));

        assertThatCode(() -> validate("ibm-ubuntu-24-04-4-minimal-amd64-7")).doesNotThrowAnyException();
    }

    @Test
    void whenTheCloudCannotBeReadTheChoiceIsNotBlocked() {
        /*
         * 목록 자체가 비면 CSP 를 못 읽는 상황이다. 여기서 막으면 CSP 장애 때 정상 요청까지
         * 거절한다 — 그때는 Pulumi 가 더 정확한 진단을 준다.
         */
        when(vmOptionsService.getImages(anyString(), anyString(), anyString(), any(), any(), any(), anyInt()))
                .thenReturn(List.of());

        assertThatCode(() -> validate("anything")).doesNotThrowAnyException();
    }

    @Test
    void theValidatorUsesTheCachedLookup() throws Exception {
        // QueryService 를 직접 부르면 preflight 마다 CSP API 를 200건씩 새로 훑는다.
        String source = java.nio.file.Files.readString(java.nio.file.Path.of(
                "src/main/java", VmOptionsSelectionValidator.class.getName().replace('.', '/') + ".java"));

        assertThat(source).doesNotContain("VmOptionsQueryService");
    }

    @Test
    void anIdentifierThatIsNotAName_isFoundInTheFullList() {
        /*
         * OCI 는 OCID 를 고른다. 그 값을 키워드로 넘기면 이름 검색이라 아무것도 안 걸려,
         * 멀쩡한 이미지가 없는 이미지로 판정됐다.
         */
        String ocid = "ocid1.image.oc1.ap-tokyo-1.aaaaaaaaynlmwelpk5holjxguc3jmwitb";
        when(vmOptionsService.getImages(anyString(), anyString(), anyString(), eq(ocid), any(), any(), anyInt()))
                .thenReturn(List.of());
        when(vmOptionsService.getImages(anyString(), anyString(), anyString(), eq(null), any(), any(), anyInt()))
                .thenReturn(List.of(VmOptionImage.builder()
                        .id(ocid)
                        .name("Canonical-Ubuntu-24.04-2025.01.17-0")
                        .build()));

        assertThatCode(() -> validate(ocid)).doesNotThrowAnyException();
    }
}
