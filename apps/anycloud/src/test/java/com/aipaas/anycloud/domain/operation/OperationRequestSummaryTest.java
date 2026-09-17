package com.aipaas.anycloud.domain.operation;

import static org.assertj.core.api.Assertions.assertThat;

import com.aipaas.anycloud.domain.operation.model.OperationState;
import com.aipaas.anycloud.domain.operation.model.OperationType;
import com.aipaas.anycloud.testsupport.AbstractUnitTest;
import org.junit.jupiter.api.Test;

/**
 * 작업이 무엇을 했는지 보여준다.
 *
 * <p>CREATE_CLUSTER 가 수십 건인데 각각 어떤 요청이었는지 알 수 없어 목록을 봐도 구분이 되지
 * 않았다. 요청 본문은 이미 저장돼 있었고 응답에만 빠져 있었다.
 */
class OperationRequestSummaryTest extends AbstractUnitTest {

    private OperationEntity entity(String payload) {
        OperationEntity e = new OperationEntity();
        e.setId("op-1");
        e.setType(OperationType.CREATE_CLUSTER);
        e.setState(OperationState.RUNNING);
        e.setRequestPayload(payload);
        return e;
    }

    @Test
    void exposesTheStoredRequest() {
        String payload = "{\"clusterName\":\"demo\",\"spec\":{\"region\":\"ap-tokyo-1\"}}";

        assertThat(OperationResponse.from(entity(payload)).request()).contains("ap-tokyo-1");
    }

    @Test
    void redactsSecretsInTheRequest() {
        // 요청 본문에 자격증명이 섞여 들어오면 작업 이력이 비밀 저장소가 된다.
        String payload = "{\"AWS_SECRET_ACCESS_KEY\":\"wJalrXUtnFEMIK7MDENGbPxRfiCYEXAMPLEKEY\"}";

        assertThat(OperationResponse.from(entity(payload)).request())
                .doesNotContain("wJalrXUtnFEMIK7MDENGbPxRfiCYEXAMPLEKEY");
    }

    @Test
    void missingRequestStaysNull() {
        assertThat(OperationResponse.from(entity(null)).request()).isNull();
    }
}
