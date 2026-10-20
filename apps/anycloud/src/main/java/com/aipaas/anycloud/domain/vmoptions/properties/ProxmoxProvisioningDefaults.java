package com.aipaas.anycloud.domain.vmoptions.properties;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Proxmox 일괄 프로비저닝이 집어갈 기본값.
 *
 * <p>일괄 생성 화면은 config-schema 의 {@code defaultValue} 만으로 providerSpec 구성. 설치
 * 환경마다 주소가 달라 저장소 고정 불가, 미지정 시 Proxmox 차단. env 로 주입.
 *
 * <p>노드 수만큼의 주소를 한 벌만 보유하므로 Proxmox 클러스터 동시 2개 이상 생성 불가. 두 번째
 * 요청이 동일 주소 선점. 검증용 단일 클러스터 전제.
 *
 * @param nodeIps 쉼표로 구분한 고정 IPv4. master 선행, worker 후행
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
