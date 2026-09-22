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
 * PVE 에서 고를 수 있는 값을 읽어 온다.
 *
 * <p>노드 이름, datastore, 브리지는 호스트마다 다르다. 사용자가 콘솔을 열어 베껴 적게 두면 오타가
 * 프로비저닝 직전까지 드러나지 않는다.
 *
 * <p>인스턴스 타입 카탈로그는 없다 — Proxmox 는 코어와 메모리를 직접 받는다. 그래서 스펙과 이미지
 * 목록은 비워 두고, 화면이 직접 입력으로 그린다.
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

    /** Proxmox 는 하이퍼바이저라 리전이 없다. 배치할 노드가 그 자리를 대신한다. */
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

    /** 카탈로그가 없다. 사용자가 "코어-메모리MiB" 를 직접 넣는다. */
    @Override
    public List<VmOptionSpec> listSpecs(String region, String keyword, boolean gpuOnly, int limit) {
        return List.of();
    }

    /** 이미지도 URL 로 받는다. 목록을 흉내 내면 없는 선택지를 고르게 된다. */
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
                // 디스크는 블록 스토리지, 내려받은 이미지는 import content 를 받는 디렉터리 스토리지로 간다.
            case "providerSpec.datastoreId" -> storages(env, region, "images");
            case "providerSpec.imageDatastoreId" -> storages(env, region, "import");
            case "providerSpec.networkBridge" -> bridges(env, region);
            default -> List.of();
        };
    }

    private List<String> listConfigOptionsFallback(String configKey, String region, Throwable throwable) {
        // 조회가 막혀도 자유 입력으로 남는다. 목록을 못 준다고 생성을 막을 이유는 없다.
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
     * @param content datastore 가 받는 content type. 블록 스토리지에 이미지를 내려받거나 디렉터리
     *     스토리지에 디스크를 만들려 하면 PVE 가 거절한다
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
     * 화면은 리전을 노드 이름으로 채운다. 아직 고르지 않았으면 첫 노드로 목록을 보여 준다 —
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
