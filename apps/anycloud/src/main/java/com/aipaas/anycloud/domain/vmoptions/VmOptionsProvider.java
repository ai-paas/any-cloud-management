package com.aipaas.anycloud.domain.vmoptions;

import com.aipaas.anycloud.domain.provisioning.model.SupportedProvisioningProvider;
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
