package com.aipaas.anycloud.common.error.exception.provisioning;

import static org.assertj.core.api.Assertions.assertThat;

import com.aipaas.anycloud.testsupport.AbstractUnitTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * 실패 원문은 Pulumi stdout 수백 줄이다.
 *
 * <p>그 안에서 한 조각을 찾아내는 것은 이 시스템을 만든 사람만 할 수 있다. 아래 원문은 모두
 * 실제로 겪은 것들이다.
 */
class ProvisioningFailureReasonTest extends AbstractUnitTest {

    @Test
    void ociCapacityShortageIsRecognised() {
        String raw =
                """
                Pulumi automation up failed: code: 1
                 +  oci:Core:Instance master creating (1s) error:   sdk-v2/provider2.go:571:
                 sdk.helper_schema: 500-InternalError, Out of host capacity.
                """;

        ProvisioningFailureReason reason = ProvisioningFailureReason.from(raw);

        assertThat(reason).isEqualTo(ProvisioningFailureReason.OUT_OF_CAPACITY);
        assertThat(reason.hint()).contains("다른 인스턴스 타입");
    }

    @Test
    void sshRejectionIsRecognised() {
        // Alibaba 의 Ubuntu 이미지에는 ubuntu 계정이 없어 기본 사용자로 붙으면 거절된다.
        String raw = "Failed during node preparation on host 43.108.80.148:22 after 10 attempts: "
                + "ssh: Permission denied (publickey)";

        assertThat(ProvisioningFailureReason.from(raw)).isEqualTo(ProvisioningFailureReason.NODE_LOGIN_REJECTED);
    }

    @Test
    void missingConfigIsRecognised() {
        String raw = "Missing required provisioning config: anycloud-k8s:providerSpec.compartmentId";

        assertThat(ProvisioningFailureReason.from(raw)).isEqualTo(ProvisioningFailureReason.CONFIG_MISSING);
    }

    @Test
    void credentialRejectionIsRecognised() {
        assertThat(ProvisioningFailureReason.from("InvalidAccessKeyId: The key does not exist"))
                .isEqualTo(ProvisioningFailureReason.CREDENTIAL_REJECTED);
    }

    @Test
    void theIbmImagePanicIsRecognised() {
        /*
         * 없는 이미지를 주면 pulumi-yaml 이 패닉해 Go 스택이 그대로 올라온다. 사용자는 수백 줄
         * 중에 무엇이 문제인지 알 길이 없다.
         */
        String raw =
                """
                error: rpc error: code = Unknown desc = invocation of ibm:index/getIsImage:getIsImage failed
                error: Error registering variable [image]: no diagnostics
                panic: fatal: An assertion has failed: to must be a struct type
                """;

        assertThat(ProvisioningFailureReason.from(raw)).isEqualTo(ProvisioningFailureReason.IMAGE_NOT_FOUND);
    }

    @Test
    void anUnknownFailureStaysUnknown() {
        /*
         * 억지로 분류하면 엉뚱한 안내를 보여주고 원문을 볼 생각을 막는다. 모르면 모른다고 두고
         * 화면이 원문을 보여준다.
         */
        assertThat(ProvisioningFailureReason.from("something we have never seen"))
                .isNull();
        assertThat(ProvisioningFailureReason.from(null)).isNull();
        assertThat(ProvisioningFailureReason.from("  ")).isNull();
    }

    @ParameterizedTest
    @EnumSource(ProvisioningFailureReason.class)
    void everyReasonTellsTheUserWhatToDo(ProvisioningFailureReason reason) {
        // 원인만 알려주고 할 일을 안 주면 결국 원문을 열게 된다.
        assertThat(reason.summary()).isNotBlank();
        assertThat(reason.hint()).isNotBlank();
    }
}
