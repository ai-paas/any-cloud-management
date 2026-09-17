package com.aipaas.anycloud.domain.credential;

import static org.assertj.core.api.Assertions.assertThat;

import com.aipaas.anycloud.testsupport.AbstractUnitTest;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * 자격증명에서 무엇을 바꿔도 되는지.
 *
 * <p>이름과 프로바이더는 막는다 — vm_cluster 가 이름을 기록으로 들고 있고(삭제된 자격증명의
 * 유일한 흔적이다), 프로바이더가 바뀌면 키 구성 자체가 달라진다.
 */
class CredentialUpdateRulesTest extends AbstractUnitTest {

    @Test
    void descriptionAloneIsAValidChange() {
        assertThat(CredentialUpdateRules.replacesValues(null)).isFalse();
        assertThat(CredentialUpdateRules.replacesValues(Map.of())).isFalse();
    }

    @Test
    void valuesAreReplacedWholesaleNotMerged() {
        // 부분 수정은 AWS 처럼 짝이 있는 키에서 못 쓰는 조합을 만든다.
        assertThat(CredentialUpdateRules.replacesValues(Map.of("AWS_ACCESS_KEY_ID", "x")))
                .isTrue();
    }

    @Test
    void blankValuesAreRejected() {
        // 빈 값으로 덮으면 조용히 못 쓰는 자격증명이 된다.
        assertThat(CredentialUpdateRules.hasBlankValue(Map.of("A", "x", "B", " ")))
                .isTrue();
        assertThat(CredentialUpdateRules.hasBlankValue(Map.of("A", "x"))).isFalse();
    }

    @Test
    void replacingValuesInvalidatesTheKnownHealth() {
        // 바꾼 키가 틀려도 다음 프로비저닝이 실패할 때까지 몰랐다.
        assertThat(CredentialUpdateRules.shouldRecheckHealth(Map.of("A", "x"))).isTrue();
        assertThat(CredentialUpdateRules.shouldRecheckHealth(null)).isFalse();
    }
}
