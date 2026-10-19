package com.aipaas.anycloud.domain.vmoptions.properties;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Proxmox 일괄 프로비저닝이 집어갈 기본값.
 *
 * <p>일괄 창은 config-schema 의 {@code defaultValue} 만 보고 providerSpec 을 만든다. 설치마다
 * 다른 주소라 저장소에 박을 수 없고, 비워 두면 Proxmox 가 차단된다. 그래서 env 로 받는다.
 *
 * <p>노드 수만큼의 주소를 한 벌만 들고 있으므로 Proxmox 클러스터를 **동시에 둘 이상 만들 수
 * 없다.** 두 번째 요청이 같은 주소를 집는다. 검증용 단일 클러스터를 전제로 한 값이다.
 *
 * @param nodeIps 쉼표로 이은 고정 IPv4. master 가 먼저고 worker 가 뒤따른다
 * @param gateway 고정 주소를 쓸 때의 기본 게이트웨이
 * @param sshHost 부트스트랩이 붙을 주소. 백엔드가 노드와 다른 망일 때만 채운다
 * @param sshPorts {@code sshHost} 의 포트. {@code nodeIps} 와 같은 순서
 */
@ConfigurationProperties(prefix = "anycloud.provisioning.proxmox")
public record ProxmoxProvisioningDefaults(String nodeIps, String gateway, String sshHost, String sshPorts) {

    public ProxmoxProvisioningDefaults {
        nodeIps = blankToNull(nodeIps);
        gateway = blankToNull(gateway);
        sshHost = blankToNull(sshHost);
        sshPorts = blankToNull(sshPorts);
    }

    /** 빈 문자열을 그대로 defaultValue 에 넣으면 일괄 창이 값이 있는 것으로 읽는다. */
    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
