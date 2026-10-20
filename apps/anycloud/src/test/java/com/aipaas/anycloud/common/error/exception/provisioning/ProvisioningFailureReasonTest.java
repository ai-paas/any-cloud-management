package com.aipaas.anycloud.common.error.exception.provisioning;

import static org.assertj.core.api.Assertions.assertThat;

import com.aipaas.anycloud.testsupport.AbstractUnitTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * 실패 원문은 Pulumi stdout 수백 줄.
 *
 * <p>그 안에서 해당 조각을 찾으려면 시스템 내부 지식 필요. 아래 원문은 모두 실제 발생 사례.
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
        // Alibaba Ubuntu 이미지에는 ubuntu 계정이 없어 기본 사용자 접속 시 거절.
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
         * 존재하지 않는 이미지 전달 시 pulumi-yaml 패닉으로 Go 스택 노출. 사용자는 수백 줄에서
         * 문제 지점 식별 불가.
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
         * 임의 분류 시 잘못된 안내를 표시하고 원문 확인을 차단. 미분류 상태로 두고 화면이
         * 원문 표시.
         */
        assertThat(ProvisioningFailureReason.from("something we have never seen"))
                .isNull();
        assertThat(ProvisioningFailureReason.from(null)).isNull();
        assertThat(ProvisioningFailureReason.from("  ")).isNull();
    }

    @ParameterizedTest
    @EnumSource(ProvisioningFailureReason.class)
    void everyReasonTellsTheUserWhatToDo(ProvisioningFailureReason reason) {
        // 원인만 제공하고 조치를 안내하지 않으면 결국 원문 확인 필요.
        assertThat(reason.summary()).isNotBlank();
        assertThat(reason.hint()).isNotBlank();
    }

    @Test
    void gcpZoneStockoutReadsAsCapacity() {
        String raw = "error: Error waiting for instance to create: The zone "
                + "'projects/p/zones/asia-northeast3-a' does not have enough resources available to "
                + "fulfill the request. '(resource type:compute)'.";

        assertThat(ProvisioningFailureReason.from(raw)).isEqualTo(ProvisioningFailureReason.OUT_OF_CAPACITY);
    }

    @Test
    void alibabaRiskControlPointsAtTheAccount() {
        String raw = "SDKError: StatusCode: 403 Code: Forbidden.RiskControl Message: ...";

        assertThat(ProvisioningFailureReason.from(raw))
                .isEqualTo(ProvisioningFailureReason.ACCOUNT_VERIFICATION_REQUIRED);
    }

    @Test
    void novaErrorStateIsNotSilent() {
        String raw = "Error waiting for instance (e05e68c6) to become ready: unexpected state 'ERROR', "
                + "wanted target 'ACTIVE'.";

        assertThat(ProvisioningFailureReason.from(raw)).isEqualTo(ProvisioningFailureReason.NODE_CREATE_FAILED);
    }
}
