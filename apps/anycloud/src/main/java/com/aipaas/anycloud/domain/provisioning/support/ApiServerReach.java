package com.aipaas.anycloud.domain.provisioning.support;

import java.net.InetAddress;
import java.net.URI;

/**
 * 내려받은 kubeconfig 로 바로 닿는지.
 *
 * <p>사설망에 만든 클러스터는 kubeconfig 를 받아도 그 자리에서 쓸 수 없다. 받아서 써 본 뒤에야
 * 아는 것이 지금 동작이라, 화면이 미리 말해 줄 근거를 만든다.
 */
public enum ApiServerReach {
    /** 공인 주소이고 점프도 필요 없다. */
    DIRECT,
    /** API 서버 주소가 사설 대역이다 — 같은 망이나 VPN 에서만 닿는다. */
    PRIVATE_NETWORK,
    /** 노드에 점프 호스트를 거쳐야 닿는다. API 서버도 같은 망 안에 있다. */
    VIA_BASTION;

    public static ApiServerReach of(String apiServerUrl, boolean hasJumpHost) {
        if (hasJumpHost) {
            return VIA_BASTION;
        }
        return isPrivate(hostOf(apiServerUrl)) ? PRIVATE_NETWORK : DIRECT;
    }

    private static String hostOf(String url) {
        if (url == null || url.isBlank()) {
            return null;
        }
        try {
            return URI.create(url).getHost();
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /**
     * RFC1918 과 링크로컬.
     *
     * <p>이름으로 판단하지 않는다 — DNS 를 타면 조회 실패가 곧 "사설" 로 읽혀 공인 클러스터까지
     * 경고가 붙는다. 숫자 주소일 때만 본다.
     */
    private static boolean isPrivate(String host) {
        if (host == null || !host.matches("\\d{1,3}(\\.\\d{1,3}){3}")) {
            return false;
        }
        try {
            InetAddress address = InetAddress.getByName(host);
            return address.isSiteLocalAddress() || address.isLinkLocalAddress() || address.isLoopbackAddress();
        } catch (Exception e) {
            return false;
        }
    }
}
