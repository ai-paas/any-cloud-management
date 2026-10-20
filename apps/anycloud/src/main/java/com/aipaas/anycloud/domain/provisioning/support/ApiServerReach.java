package com.aipaas.anycloud.domain.provisioning.support;

import java.net.InetAddress;
import java.net.URI;

/**
 * 수신한 kubeconfig 의 직접 접근 가능 여부.
 *
 * <p>사설망 클러스터는 kubeconfig 수신 후에도 현재 위치에서 사용 불가. 사용 시도 후에야 확인되는
 * 동작이라 화면에 사전 안내할 근거 제공.
 */
public enum ApiServerReach {
    /** 공인 주소, 점프 호스트 불필요. */
    DIRECT,
    /** API 서버 주소가 사설 대역. 동일 망 또는 VPN 에서만 접근 가능. */
    PRIVATE_NETWORK,
    /** 노드 접근에 점프 호스트 필요. API 서버도 동일 망 내부. */
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
     * <p>이름 기반 판정 미사용. DNS 조회 경로에서는 조회 실패가 사설로 판정돼 공인 클러스터에도
     * 경고 표시. 숫자 주소만 판정 대상.
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
