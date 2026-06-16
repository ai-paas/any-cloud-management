package com.aipaas.anycloud.domain.provisioning.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;

import com.aipaas.anycloud.testsupport.AbstractUnitTest;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;

class KubeadmJoinTokensTest extends AbstractUnitTest {

    /** kubeadm 이 요구하는 고정 포맷 — 위반 시 kubeadm init/join 자체가 거부. */
    @RepeatedTest(20)
    void generate_matchesKubeadmTokenFormat() {
        assertThat(KubeadmJoinTokens.generate()).matches("^[a-z0-9]{6}\\.[a-z0-9]{16}$");
    }

    @Test
    void generate_producesUniqueTokens() {
        Set<String> tokens = new HashSet<>();
        for (int i = 0; i < 1000; i++) {
            tokens.add(KubeadmJoinTokens.generate());
        }
        // SecureRandom 22 자리 — 1000 회에서 충돌은 사실상 불가능. 충돌 발견 = 구현 결함.
        assertThat(tokens).hasSize(1000);
        assertThat(tokens).doesNotContain("abcdef.0123456789abcdef");
    }
}
