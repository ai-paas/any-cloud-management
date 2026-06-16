package com.aipaas.anycloud.domain.vmoptions;

import com.aipaas.anycloud.domain.provisioning.model.SupportedProvisioningProvider;
import com.aipaas.anycloud.domain.vmoptions.api.VmOptionImage;
import com.aipaas.anycloud.domain.vmoptions.api.VmOptionProvider;
import com.aipaas.anycloud.domain.vmoptions.api.VmOptionRegion;
import com.aipaas.anycloud.domain.vmoptions.api.VmOptionSpec;
import java.util.List;
import java.util.Map;

/**
 * VM options provider 추상 — 각 CSP 의 metadata 조회 (regions / specs / images) 통일.
 *
 * <p>credentials map 을 받는 overload 도입 (사용자가 등록한 credential 의 decrypted keys).
 * 기본 default 구현은 단순히 no-arg 버전으로 위임 — provider 가 자체적으로 env 변수에서 키를
 * 읽는 기존 동작 유지. 새로운 provider 나 refactor 된 provider 는 map-aware 버전을 override
 * 해서 사용자별 credential 을 활용한다 (multi-tenant 시나리오).
 */
public interface VmOptionsProvider {

    SupportedProvisioningProvider getProvider();

    VmOptionProvider describe();

    List<VmOptionRegion> listRegions();

    List<VmOptionSpec> listSpecs(String region, String keyword, boolean gpuOnly, int limit);

    List<VmOptionImage> listImages(String region, String keyword, String architecture, String owner, int limit);

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
}
