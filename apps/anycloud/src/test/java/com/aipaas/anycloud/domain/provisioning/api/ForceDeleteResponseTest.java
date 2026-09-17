package com.aipaas.anycloud.domain.provisioning.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.aipaas.anycloud.domain.provisioning.api.response.ForceDeleteResponse;
import com.aipaas.anycloud.testsupport.AbstractUnitTest;
import java.util.List;
import org.junit.jupiter.api.Test;

/** 강제 삭제는 무엇이 남을 수 있는지 반드시 알려야 한다. */
class ForceDeleteResponseTest extends AbstractUnitTest {

    @Test
    void warnsWhenStacksMayRemain() {
        var body = ForceDeleteResponse.of(2, List.of("anycloud-OpenStack-dev-demo"));

        assertThat(body.warning()).contains("남아 있을 수 있");
        assertThat(body.orphanedStacks()).containsExactly("anycloud-OpenStack-dev-demo");
    }

    @Test
    void saysNothingRemainsWhenThereWasNoStack() {
        // 없는 위험을 경고하면 진짜 경고를 무시하게 된다.
        var body = ForceDeleteResponse.of(1, List.of());

        assertThat(body.warning()).contains("남는 것이 없");
        assertThat(body.orphanedStacks()).isEmpty();
    }

    @Test
    void reportsTheRecordCount() {
        assertThat(ForceDeleteResponse.of(6, List.of()).removedRecords()).isEqualTo(6);
    }
}
