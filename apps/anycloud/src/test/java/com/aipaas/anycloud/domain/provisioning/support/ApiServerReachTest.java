package com.aipaas.anycloud.domain.provisioning.support;

import static org.assertj.core.api.Assertions.assertThat;

import com.aipaas.anycloud.testsupport.AbstractUnitTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * 사설망 클러스터는 kubeconfig 수신 후에도 현재 위치에서 사용 불가.
 *
 * <p>사용 시도 후에야 확인되는 동작. OpenStack 은 floating IP 가 사설 대역이고 노드 접근에
 * bastion 경유 필요.
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
        // 노드 접근에 점프가 필요하면 API 서버도 동일 망 내부.
        assertThat(ApiServerReach.of("https://3.38.147.151:6443", true)).isEqualTo(ApiServerReach.VIA_BASTION);
    }

    @Test
    void aHostnameIsNotGuessed() {
        /*
         * 이름 기반 판정 시 DNS 조회 실패가 사설로 판정돼 공인 클러스터에도 경고 표시.
         * 숫자 주소만 판정 대상.
         */
        assertThat(ApiServerReach.of("https://api.example.com:6443", false)).isEqualTo(ApiServerReach.DIRECT);
    }

    @Test
    void anUnknownAddressIsNotFlagged() {
        assertThat(ApiServerReach.of(null, false)).isEqualTo(ApiServerReach.DIRECT);
        assertThat(ApiServerReach.of("   ", false)).isEqualTo(ApiServerReach.DIRECT);
    }
}
