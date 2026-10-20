package com.aipaas.anycloud.domain.provisioning.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.aipaas.anycloud.domain.provisioning.api.response.ForceDeleteResponse;
import com.aipaas.anycloud.testsupport.AbstractUnitTest;
import java.util.List;
import org.junit.jupiter.api.Test;

/** 강제 삭제는 잔존 가능 항목을 반드시 통보. */
class ForceDeleteResponseTest extends AbstractUnitTest {

    @Test
    void warnsWhenStacksMayRemain() {
        var body = ForceDeleteResponse.of(2, List.of("anycloud-OpenStack-dev-demo"));

        assertThat(body.warning()).contains("남아 있을 수 있");
        assertThat(body.orphanedStacks()).containsExactly("anycloud-OpenStack-dev-demo");
    }

    @Test
    void saysNothingRemainsWhenThereWasNoStack() {
        // 존재하지 않는 위험을 경고하면 실제 경고의 신뢰도 저하.
        var body = ForceDeleteResponse.of(1, List.of());

        assertThat(body.warning()).contains("남는 것이 없");
        assertThat(body.orphanedStacks()).isEmpty();
    }

    @Test
    void reportsTheRecordCount() {
        assertThat(ForceDeleteResponse.of(6, List.of()).removedRecords()).isEqualTo(6);
    }
}
