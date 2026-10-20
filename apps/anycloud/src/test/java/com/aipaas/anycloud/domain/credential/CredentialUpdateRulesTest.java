package com.aipaas.anycloud.domain.credential;

import static org.assertj.core.api.Assertions.assertThat;

import com.aipaas.anycloud.testsupport.AbstractUnitTest;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * 자격증명의 변경 허용 범위.
 *
 * <p>이름과 프로바이더는 변경 차단. vm_cluster 가 이름을 기록으로 보유하며(삭제된 자격증명의
 * 유일한 흔적), 프로바이더 변경 시 키 구성 자체가 달라짐.
 */
class CredentialUpdateRulesTest extends AbstractUnitTest {

    @Test
    void descriptionAloneIsAValidChange() {
        assertThat(CredentialUpdateRules.replacesValues(null)).isFalse();
        assertThat(CredentialUpdateRules.replacesValues(Map.of())).isFalse();
    }

    @Test
    void valuesAreReplacedWholesaleNotMerged() {
        // 부분 수정은 AWS 처럼 짝을 이루는 키에서 사용 불가 조합 생성.
        assertThat(CredentialUpdateRules.replacesValues(Map.of("AWS_ACCESS_KEY_ID", "x")))
                .isTrue();
    }

    @Test
    void blankValuesAreRejected() {
        // 빈 값으로 덮으면 무음으로 사용 불가 자격증명이 됨.
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
