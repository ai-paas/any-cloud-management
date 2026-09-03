package com.aipaas.anycloud.domain.agent.bootstrap;

import java.net.InetAddress;
import java.net.UnknownHostException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * 부팅 직후 {@code agent.grpc.public-endpoint} 설정이 운영 가능한 상태인지 1회 점검 후 로그.
 * <p>
 * 본 endpoint 는 원격 K8s cluster 의 agent pod 가 backend 로 outbound 접속하는 주소.
 * 잘못 설정되면 agent 가 CrashLoopBackOff 로 들어간 다음에야 발견 — 운영자가 사전에
 * 알 수 있도록 부팅 시점에 경고.
 * <ul>
 *   <li>{@code host.docker.internal} (dev 의 default) → docker-desktop 외부에선 NXDOMAIN. WARN.</li>
 *   <li>{@code localhost} / {@code 127.0.0.1} → 원격 cluster 에서 의미 없음. WARN.</li>
 *   <li>DNS resolve 실패 → typo / 인터넷 분리 의심. WARN.</li>
 *   <li>그 외 → INFO 로 resolved IP 까지 출력 (운영자 확인 편의).</li>
 * </ul>
 * 본 점검은 부팅을 막지 않음 — 단순 진단 로그. 부팅 차단이 필요하면 별도 strict flag 도입.
 */
@Slf4j
@Component
public class AgentEndpointStartupCheck {

    private static final String DEFAULT_DEV_HOST = "host.docker.internal";

    private final String endpoint;

    public AgentEndpointStartupCheck(
            @Value("${agent.grpc.public-endpoint:${ANYCLOUD_AGENT_GRPC_PUBLIC_ENDPOINT:host.docker.internal:9090}}")
                    String endpoint) {
        this.endpoint = endpoint;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void check() {
        String host = extractHost(endpoint);
        String port = extractPortOrEmpty(endpoint);

        if (host == null || host.isBlank()) {
            log.warn(
                    """

					╔══════════════════════════════════════════════════════════════════════
					║  agent.grpc.public-endpoint 값이 비어있거나 형식 오류 ({})
					║  cluster-agent chart 설치 시 backend.grpcAddr 자동 주입 불가.
					║  환경변수 ANYCLOUD_AGENT_GRPC_PUBLIC_ENDPOINT 를 host:port 형식으로 설정하세요.
					╚══════════════════════════════════════════════════════════════════════
					""",
                    endpoint);
            return;
        }

        if (isLocalLoopback(host)) {
            log.warn(
                    """

					╔══════════════════════════════════════════════════════════════════════
					║  agent.grpc.public-endpoint = {}
					║
					║  이 값은 docker-desktop 안 또는 같은 머신에서만 의미가 있습니다.
					║  원격 K8s cluster 의 agent pod 는 이 주소로 backend 를 reach 할 수 없습니다.
					║
					║  대안:
					║    - LAN          : 노트북 IP (예: 192.168.0.42:9090)
					║    - dev tunnel   : cloudflared / ngrok / tailscale 의 public host
					║    - 운영         : ingress / LB / NodePort 의 public DNS
					║
					║  환경변수 ANYCLOUD_AGENT_GRPC_PUBLIC_ENDPOINT 로 override.
					╚══════════════════════════════════════════════════════════════════════
					""",
                    endpoint);
            // loopback 은 어차피 resolve 됨 → DNS 추가 점검 skip.
            return;
        }

        // DNS preflight — typo / VPN 단절 / 사설 DNS 인지 부팅 시점에 잡아냄.
        try {
            InetAddress resolved = InetAddress.getByName(host);
            log.info(
                    "agent.grpc.public-endpoint OK — host={}, resolved={}, port={}",
                    host,
                    resolved.getHostAddress(),
                    port);
        } catch (UnknownHostException e) {
            log.warn(
                    """

					╔══════════════════════════════════════════════════════════════════════
					║  agent.grpc.public-endpoint = {}
					║
					║  DNS resolve 실패 (host={}). 다음을 확인하세요:
					║    1. 오타 — agent.aipas.example.com vs agent.aipaas.example.com 처럼.
					║    2. VPN/Tailscale 분리 — 사내 DNS 가 끊겼을 때.
					║    3. private DNS — 컨테이너 안에서 reach 가능한지.
					║  본 호스트가 실제로 resolve 되어야 원격 cluster 도 같은 결과가 나옵니다.
					╚══════════════════════════════════════════════════════════════════════
					""",
                    endpoint,
                    host);
        }
    }

    /* ---------- helpers ---------- */

    /**
     * "host:port" 또는 "host" 형식에서 host 부분만 추출.
     * IPv6 zone-id 등 복잡 케이스는 의도적 단순화 — dev 친화 진단용.
     */
    private static String extractHost(String endpoint) {
        if (endpoint == null) {
            return null;
        }
        int colon = endpoint.lastIndexOf(':');
        if (colon <= 0) {
            return endpoint;
        }
        return endpoint.substring(0, colon);
    }

    private static String extractPortOrEmpty(String endpoint) {
        if (endpoint == null) {
            return "";
        }
        int colon = endpoint.lastIndexOf(':');
        if (colon < 0 || colon == endpoint.length() - 1) {
            return "";
        }
        return endpoint.substring(colon + 1);
    }

    private static boolean isLocalLoopback(String host) {
        return DEFAULT_DEV_HOST.equalsIgnoreCase(host)
                || "localhost".equalsIgnoreCase(host)
                || "127.0.0.1".equals(host)
                || "0.0.0.0".equals(host)
                || "::1".equals(host);
    }
}
