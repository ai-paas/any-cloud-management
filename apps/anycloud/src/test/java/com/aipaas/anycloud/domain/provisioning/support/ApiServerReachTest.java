package com.aipaas.anycloud.domain.provisioning.support;

import static org.assertj.core.api.Assertions.assertThat;

import com.aipaas.anycloud.testsupport.AbstractUnitTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * 사설망 클러스터는 kubeconfig 를 받아도 그 자리에서 못 쓴다.
 *
 * <p>받아서 써 본 뒤에야 아는 것이 지금 동작이다 — OpenStack 은 floating IP 가 사설 대역이고
 * 노드에 닿으려면 bastion 을 거친다.
 */
class ApiServerReachTest extends AbstractUnitTest {

    @Test
    void aPublicAddressWithoutAJumpHostIsDirect() {
        assertThat(ApiServerReach.of("https://3.38.147.151:6443", false)).isEqualTo(ApiServerReach.DIRECT);
    }

    @ParameterizedTest
    @ValueSource(strings = {"https://10.90.0.52:6443", "https://192.168.0.200:6443", "https://172.16.4.9:6443"})
    void aPrivateAddressIsFlagged(String url) {
        assertThat(ApiServerReach.of(url, false)).isEqualTo(ApiServerReach.PRIVATE_NETWORK);
    }

    @Test
    void aJumpHostMeansTheClusterSitsBehindABastion() {
        // 노드에 점프가 필요하면 API 서버도 같은 망 안에 있다.
        assertThat(ApiServerReach.of("https://3.38.147.151:6443", true)).isEqualTo(ApiServerReach.VIA_BASTION);
    }

    @Test
    void aHostnameIsNotGuessed() {
        /*
         * 이름으로 판단하면 DNS 조회 실패가 곧 "사설" 로 읽혀 공인 클러스터까지 경고가 붙는다.
         * 숫자 주소일 때만 본다.
         */
        assertThat(ApiServerReach.of("https://api.example.com:6443", false)).isEqualTo(ApiServerReach.DIRECT);
    }

    @Test
    void anUnknownAddressIsNotFlagged() {
        assertThat(ApiServerReach.of(null, false)).isEqualTo(ApiServerReach.DIRECT);
        assertThat(ApiServerReach.of("   ", false)).isEqualTo(ApiServerReach.DIRECT);
    }
}
