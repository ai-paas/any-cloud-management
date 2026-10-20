package com.aipaas.anycloud.domain.operation;

import static org.assertj.core.api.Assertions.assertThat;

import com.aipaas.anycloud.domain.operation.model.OperationState;
import com.aipaas.anycloud.domain.operation.model.OperationType;
import com.aipaas.anycloud.testsupport.AbstractUnitTest;
import org.junit.jupiter.api.Test;

/**
 * 작업 내용 표시.
 *
 * <p>CREATE_CLUSTER 가 수십 건인 상태에서 각 요청 내용을 확인할 수 없어 목록에서 구분 불가.
 * 요청 본문은 이미 저장된 상태였고 응답에만 누락.
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
        // 요청 본문에 자격증명이 포함되면 작업 이력이 비밀값 저장소가 됨.
        String payload = "{\"AWS_SECRET_ACCESS_KEY\":\"wJalrXUtnFEMIK7MDENGbPxRfiCYEXAMPLEKEY\"}";

        assertThat(OperationResponse.from(entity(payload)).request())
                .doesNotContain("wJalrXUtnFEMIK7MDENGbPxRfiCYEXAMPLEKEY");
    }

    @Test
    void missingRequestStaysNull() {
        assertThat(OperationResponse.from(entity(null)).request()).isNull();
    }
}
