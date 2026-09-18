package com.aipaas.anycloud.domain.provisioning.preflight.validation;

import com.aipaas.anycloud.common.error.enums.ErrorCode;
import com.aipaas.anycloud.common.error.exception.CustomException;
import com.aipaas.anycloud.domain.provisioning.proxmox.ProxmoxApiClient;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Proxmox 요청이 가리키는 노드, datastore, 브리지가 실제로 있는지 확인한다.
 *
 * <p>이름이 틀리면 Pulumi 가 VM 을 만들기 시작한 뒤에야 실패해서, 이미 생성된 자원이 롤백 대상으로
 * 남는다. 특히 datastore 는 설치 유형마다 이름이 달라({@code local-lvm} / {@code local-zfs}) 기본값을
 * 그대로 쓰면 ZFS 호스트에서 매번 걸린다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ProxmoxPreflightValidator {

    /** {@code Defaults.applyProviderDefaults} 와 같은 값. 어긋나면 검증이 실제 요청과 다른 것을 본다. */
    private static final String DEFAULT_DATASTORE = "local-lvm";

    private static final String DEFAULT_IMAGE_DATASTORE = "local";
    private static final String DEFAULT_BRIDGE = "vmbr0";

    private static final String KEY_NODE = "anycloud-k8s:providerSpec.nodeName";
    private static final String KEY_DATASTORE = "anycloud-k8s:providerSpec.datastoreId";
    private static final String KEY_IMAGE_DATASTORE = "anycloud-k8s:providerSpec.imageDatastoreId";
    private static final String KEY_BRIDGE = "anycloud-k8s:providerSpec.networkBridge";
    private static final String KEY_NODE_IPS = "anycloud-k8s:providerSpec.nodeIps";
    private static final String KEY_SSH_PORTS = "anycloud-k8s:providerSpec.sshPorts";
    private static final String KEY_GATEWAY = "anycloud-k8s:providerSpec.gateway";
    private static final String KEY_MASTER_COUNT = "anycloud-k8s:masterCount";
    private static final String KEY_WORKER_COUNT = "anycloud-k8s:workerCount";

    private final ProxmoxApiClient api;

    public void validate(Map<String, String> credentialEnv, Map<String, String> config) {
        validateStaticAddressing(config);
        String endpoint = trimmed(credentialEnv.get("PROXMOX_VE_ENDPOINT"));
        String tokenId = trimmed(credentialEnv.get("PROXMOX_VE_API_TOKEN_ID"));
        String tokenSecret = trimmed(credentialEnv.get("PROXMOX_VE_API_TOKEN_SECRET"));
        String node = trimmed(config.get(KEY_NODE));
        if (endpoint.isEmpty() || tokenId.isEmpty() || tokenSecret.isEmpty() || node.isEmpty()) {
            // 여기까지 오면 정적 검증이 이미 통과시킨 것이다. 중복으로 막지 않는다.
            return;
        }

        JsonNode nodes = api.get(credentialEnv, "/api2/json/nodes");
        if (nodes == null) return;

        requireNode(nodes, node);
        JsonNode storages = api.get(credentialEnv, "/api2/json/nodes/" + node + "/storage");
        if (storages != null) {
            requireStorage(storages, valueOr(config, KEY_DATASTORE, DEFAULT_DATASTORE), "images", "datastoreId");
            requireStorage(
                    storages,
                    valueOr(config, KEY_IMAGE_DATASTORE, DEFAULT_IMAGE_DATASTORE),
                    "import",
                    "imageDatastoreId");
        }
        JsonNode networks = api.get(credentialEnv, "/api2/json/nodes/" + node + "/network");
        if (networks != null) {
            requireBridge(networks, valueOr(config, KEY_BRIDGE, DEFAULT_BRIDGE));
        }
    }

    /**
     * 고정 주소는 개수가 맞아야 한다.
     *
     * <p>모자라면 남은 노드가 DHCP 로 떨어지는데, cloud 이미지에 {@code qemu-guest-agent} 가 없어
     * 받은 주소를 알 수 없다. 부트스트랩이 붙을 곳을 잃고 BOOTSTRAP 에서야 드러난다.
     */
    private void validateStaticAddressing(Map<String, String> config) {
        List<String> ips = csv(config.get(KEY_NODE_IPS));
        if (ips.isEmpty()) return;

        int expected = count(config, KEY_MASTER_COUNT, 1) + count(config, KEY_WORKER_COUNT, 0);
        if (ips.size() != expected) {
            throw reject(
                    "providerSpec.nodeIps",
                    String.join(",", ips),
                    "주소 %d 개가 노드 %d 대와 맞지 않습니다. master 가 먼저고 worker 가 뒤따릅니다".formatted(ips.size(), expected));
        }
        if (trimmed(config.get(KEY_GATEWAY)).isEmpty()) {
            throw reject("providerSpec.gateway", "", "고정 주소를 쓰면 게이트웨이가 필요합니다. 없으면 노드가 밖으로 나가지 못합니다");
        }
        List<String> ports = csv(config.get(KEY_SSH_PORTS));
        if (!ports.isEmpty() && ports.size() != ips.size()) {
            throw reject(
                    "providerSpec.sshPorts",
                    String.join(",", ports),
                    "포트 %d 개가 주소 %d 개와 맞지 않습니다. 같은 순서여야 합니다".formatted(ports.size(), ips.size()));
        }
    }

    private static int count(Map<String, String> config, String key, int fallback) {
        String value = trimmed(config.get(key));
        if (value.isEmpty()) return fallback;
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static List<String> csv(String value) {
        if (value == null || value.isBlank()) return List.of();
        return java.util.Arrays.stream(value.split(","))
                .map(String::trim)
                .filter(part -> !part.isEmpty())
                .toList();
    }

    private void requireNode(JsonNode nodes, String node) {
        List<String> names = new ArrayList<>();
        for (JsonNode n : nodes) {
            String name = n.path("node").asText("");
            if (name.isEmpty()) continue;
            names.add(name);
            if (name.equals(node)) return;
        }
        throw reject("providerSpec.nodeName", node, "노드를 찾지 못했습니다. 이 호스트의 노드: " + String.join(", ", names));
    }

    /**
     * @param requiredContent {@code images} 는 디스크를 만들 블록 스토리지, {@code import} 는 내려받은
     *     이미지를 둘 디렉터리 스토리지. 같은 datastore 가 둘 다 받는 경우는 없다
     */
    private void requireStorage(JsonNode storages, String storage, String requiredContent, String field) {
        List<String> candidates = new ArrayList<>();
        for (JsonNode s : storages) {
            String name = s.path("storage").asText("");
            String content = s.path("content").asText("");
            if (content.contains(requiredContent)) candidates.add(name);
            if (!name.equals(storage)) continue;
            if (content.contains(requiredContent)) return;
            throw reject(
                    "providerSpec." + field,
                    storage,
                    "'%s' content 가 없습니다 (현재: %s). `pvesm set %s --content %s,%s` 로 켜거나 다른 datastore 를 지정합니다"
                            .formatted(requiredContent, content, storage, content, requiredContent));
        }
        throw reject(
                "providerSpec." + field,
                storage,
                "datastore 를 찾지 못했습니다. '%s' 를 받는 datastore: %s"
                        .formatted(requiredContent, candidates.isEmpty() ? "없음" : String.join(", ", candidates)));
    }

    private void requireBridge(JsonNode networks, String bridge) {
        List<String> names = new ArrayList<>();
        for (JsonNode n : networks) {
            if (!"bridge".equals(n.path("type").asText())) continue;
            String name = n.path("iface").asText("");
            names.add(name);
            if (name.equals(bridge)) return;
        }
        throw reject("providerSpec.networkBridge", bridge, "브리지를 찾지 못했습니다. 이 노드의 브리지: " + String.join(", ", names));
    }

    private CustomException reject(String field, String value, String message) {
        return new CustomException(ErrorCode.INVALID_INPUT_VALUE, field, value, message);
    }

    private static String valueOr(Map<String, String> config, String key, String fallback) {
        String value = trimmed(config.get(key));
        return value.isEmpty() ? fallback : value;
    }

    private static String trimmed(String value) {
        return value == null ? "" : value.trim();
    }
}
