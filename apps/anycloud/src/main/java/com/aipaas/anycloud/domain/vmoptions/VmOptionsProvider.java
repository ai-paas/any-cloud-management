package com.aipaas.anycloud.domain.vmoptions;

import com.aipaas.anycloud.domain.provisioning.model.SupportedProvisioningProvider;
import com.aipaas.anycloud.domain.vmoptions.api.ConfigOption;
import com.aipaas.anycloud.domain.vmoptions.api.VmOptionImage;
import com.aipaas.anycloud.domain.vmoptions.api.VmOptionProvider;
import com.aipaas.anycloud.domain.vmoptions.api.VmOptionRegion;
import com.aipaas.anycloud.domain.vmoptions.api.VmOptionSpec;
import java.util.List;
import java.util.Map;

/** VM options provider 추상 — 각 CSP 의 metadata 조회 (regions / specs / images) 통일. */
public interface VmOptionsProvider {

    SupportedProvisioningProvider getProvider();

    VmOptionProvider describe();

    List<VmOptionRegion> listRegions();

    List<VmOptionSpec> listSpecs(String region, String keyword, boolean gpuOnly, int limit);

    List<VmOptionImage> listImages(String region, String keyword, String architecture, String owner, int limit);

    /**
     * providerSpec 키 하나가 가질 수 있는 값 목록. 열거할 수 없는 키는 빈 목록.
     *
     * <p>포탈은 이 값이 있으면 자유 입력 대신 선택 목록을 보여준다. CSP 마다 열거 가능한 키가
     * 달라(IBM zone, Proxmox nodeName, Azure resourceGroup …) 키를 인자로 받는다.
     *
     * @param configKey {@code anycloud-k8s:} 접두를 뗀 이름 (예: {@code providerSpec.zone})
     */
    default List<String> listConfigOptions(String configKey, String region) {
        return List.of();
    }

    // ---- credentials-aware overloads (default = ignore credentials, fallback to env) ----

    /** 사용자가 등록한 credential 의 decrypted keys 를 활용 — 기본 구현은 env 변수 fallback. */
    default List<VmOptionRegion> listRegions(Map<String, String> credentials) {
        return listRegions();
    }

    default List<VmOptionSpec> listSpecs(
            Map<String, String> credentials, String region, String keyword, boolean gpuOnly, int limit) {
        return listSpecs(region, keyword, gpuOnly, limit);
    }

    default List<VmOptionImage> listImages(
            Map<String, String> credentials,
            String region,
            String keyword,
            String architecture,
            String owner,
            int limit) {
        return listImages(region, keyword, architecture, owner, limit);
    }

    default List<String> listConfigOptions(Map<String, String> credentials, String configKey, String region) {
        return listConfigOptions(configKey, region);
    }

    /**
     * 값에 사람이 읽을 이름을 붙여 돌려준다.
     *
     * <p>기본 구현은 값을 그대로 이름으로 쓴다 — IBM zone 이나 Proxmox 노드처럼 식별자가 곧
     * 이름인 경우가 대부분이다. OCI compartment 처럼 OCID 와 이름이 따로인 provider 만 재정의한다.
     */
    /**
     * 그 존에 이 인스턴스 타입을 띄울 수 있는지.
     *
     * <p>리전에 있다고 모든 존에 있는 것이 아니다 — {@code a2-highgpu-1g} 는 asia-northeast3 에
     * 있지만 -a 존에는 없다. 리전 목록만 보고 통과시키면 인프라를 만든 뒤 인스턴스에서 실패한다.
     *
     * <p>모르면 {@code true} 다. 판단할 수 없다고 정상 요청을 막을 이유는 없다.
     *
     * @param zone 비우면 provider 가 기본 존을 정한다
     */
    default boolean isInstanceTypeAvailableInZone(
            Map<String, String> credentials, String region, String zone, String instanceType) {
        return true;
    }

    default List<ConfigOption> listConfigOptionsWithLabels(
            Map<String, String> credentials, String configKey, String region) {
        return listConfigOptions(credentials, configKey, region).stream()
                .map(ConfigOption::of)
                .toList();
    }
}
