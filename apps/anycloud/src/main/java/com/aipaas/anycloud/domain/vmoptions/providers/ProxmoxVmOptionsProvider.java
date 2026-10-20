package com.aipaas.anycloud.domain.vmoptions.providers;

import com.aipaas.anycloud.domain.provisioning.model.SupportedProvisioningProvider;
import com.aipaas.anycloud.domain.provisioning.proxmox.ProxmoxApiClient;
import com.aipaas.anycloud.domain.vmoptions.api.VmOptionImage;
import com.aipaas.anycloud.domain.vmoptions.api.VmOptionProvider;
import com.aipaas.anycloud.domain.vmoptions.api.VmOptionRegion;
import com.aipaas.anycloud.domain.vmoptions.api.VmOptionSpec;
import com.fasterxml.jackson.databind.JsonNode;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * PVE 에서 선택 가능한 값 조회.
 *
 * <p>노드 이름, datastore, 브리지는 호스트마다 다름. 사용자가 콘솔에서 확인해 직접 입력하면
 * 오타가 프로비저닝 단계까지 드러나지 않음.
 *
 * <p>인스턴스 타입 카탈로그 없음. Proxmox 는 코어와 메모리를 직접 지정하므로 스펙과 이미지
 * 목록은 비우고 화면이 자유 입력으로 처리.
 */
@Component
@RequiredArgsConstructor
public class ProxmoxVmOptionsProvider extends AbstractVmOptionsProvider {

    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger(ProxmoxVmOptionsProvider.class);

    private final ProxmoxApiClient api;

    @Override
    public SupportedProvisioningProvider getProvider() {
        return SupportedProvisioningProvider.PROXMOX;
    }

    @Override
    public VmOptionProvider describe() {
        return describe(getProvider(), true, "PVE API 로 노드, datastore, 브리지를 조회합니다. 인스턴스 타입은 \"코어-메모리MiB\" 입니다.");
    }

    /** Proxmox 는 하이퍼바이저라 리전 개념 부재. 배치 노드가 리전 역할 대체. */
    @Override
    @CircuitBreaker(name = "csp-api", fallbackMethod = "emptyRegionsFallback")
    public List<VmOptionRegion> listRegions() {
        return nodeNames().stream()
                .map(node -> VmOptionRegion.builder()
                        .provider(getProvider().getCanonicalName())
                        .id(node)
                        .name(node)
                        .available(true)
                        .build())
                .toList();
    }

    /** 카탈로그 부재. 사용자가 "코어-메모리MiB" 직접 입력. */
    @Override
    public List<VmOptionSpec> listSpecs(String region, String keyword, boolean gpuOnly, int limit) {
        return List.of();
    }

    /** 이미지도 URL 입력. 목록을 임의 구성하면 미제공 항목이 선택지에 포함. */
    @Override
    public List<VmOptionImage> listImages(String region, String keyword, String architecture, String owner, int limit) {
        return List.of();
    }

    @Override
    @CircuitBreaker(name = "csp-api", fallbackMethod = "listConfigOptionsFallback")
    public List<String> listConfigOptions(String configKey, String region) {
        Map<String, String> env = currentCredentials();
        return switch (configKey) {
            case "providerSpec.nodeName" -> nodeNames();
                // 디스크는 블록 스토리지, 수신 이미지는 import content 허용 디렉터리 스토리지에 배치.
            case "providerSpec.datastoreId" -> storages(env, region, "images");
            case "providerSpec.imageDatastoreId" -> storages(env, region, "import");
            case "providerSpec.networkBridge" -> bridges(env, region);
            default -> List.of();
        };
    }

    private List<String> listConfigOptionsFallback(String configKey, String region, Throwable throwable) {
        // 조회 실패 시 자유 입력으로 유지. 목록 제공 실패가 생성 차단 사유는 아님.
        LOG.warn("Proxmox config options fallback: key={} cause={}", configKey, String.valueOf(throwable));
        return List.of();
    }

    private List<VmOptionRegion> emptyRegionsFallback(Throwable throwable) {
        LOG.warn("Proxmox regions fallback: cause={}", String.valueOf(throwable));
        return List.of();
    }

    private List<String> nodeNames() {
        JsonNode nodes = api.get(currentCredentials(), "/api2/json/nodes");
        return textValues(nodes, "node");
    }

    /**
     * @param content datastore 가 허용하는 content type. 블록 스토리지에 이미지를 받거나
     *     디렉터리 스토리지에 디스크를 생성하려 하면 PVE 가 거절
     */
    private List<String> storages(Map<String, String> env, String region, String content) {
        String node = nodeOr(region, env);
        if (node == null) return List.of();
        JsonNode storages = api.get(env, "/api2/json/nodes/" + node + "/storage");
        if (storages == null) return List.of();
        List<String> out = new ArrayList<>();
        for (JsonNode storage : storages) {
            if (storage.path("content").asText("").contains(content)) {
                String name = storage.path("storage").asText("");
                if (StringUtils.hasText(name)) out.add(name);
            }
        }
        return out;
    }

    private List<String> bridges(Map<String, String> env, String region) {
        String node = nodeOr(region, env);
        if (node == null) return List.of();
        JsonNode networks = api.get(env, "/api2/json/nodes/" + node + "/network");
        if (networks == null) return List.of();
        List<String> out = new ArrayList<>();
        for (JsonNode network : networks) {
            if ("bridge".equals(network.path("type").asText(""))) {
                String name = network.path("iface").asText("");
                if (StringUtils.hasText(name)) out.add(name);
            }
        }
        return out;
    }

    /**
     * 화면은 리전을 노드 이름으로 채운다. 아직 고르지 않았으면 첫 노드로 목록을 보여 준다:
     * 단일 노드 호스트가 대부분이라 그 편이 빈 목록보다 낫다.
     */
    private String nodeOr(String region, Map<String, String> env) {
        if (StringUtils.hasText(region)) return region;
        List<String> nodes = textValues(api.get(env, "/api2/json/nodes"), "node");
        return nodes.isEmpty() ? null : nodes.get(0);
    }

    private static List<String> textValues(JsonNode array, String field) {
        if (array == null) return List.of();
        List<String> out = new ArrayList<>();
        for (JsonNode item : array) {
            String value = item.path(field).asText("");
            if (StringUtils.hasText(value)) out.add(value);
        }
        return out;
    }
}
