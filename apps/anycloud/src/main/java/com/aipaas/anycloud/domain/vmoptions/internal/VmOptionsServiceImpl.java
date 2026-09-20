package com.aipaas.anycloud.domain.vmoptions.internal;

import com.aipaas.anycloud.configuration.persistence.CacheConfig;
import com.aipaas.anycloud.domain.vmoptions.VmOptionsQueryService;
import com.aipaas.anycloud.domain.vmoptions.VmOptionsService;
import com.aipaas.anycloud.domain.vmoptions.api.ProviderConfigKey;
import com.aipaas.anycloud.domain.vmoptions.api.VmOptionImage;
import com.aipaas.anycloud.domain.vmoptions.api.VmOptionProvider;
import com.aipaas.anycloud.domain.vmoptions.api.VmOptionRegion;
import com.aipaas.anycloud.domain.vmoptions.api.VmOptionSpec;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;

/** VmOptions API facade. CSP provider 의 외부 metadata API (regions/specs/images) 호출은 매 요청마다 quota / latency 부담이 크므로 Caffeine 캐시로 30분 TTL 적용 . */
/*
 * 빈 결과는 캐시하지 않는다.
 *
 * <p>잘못된 자격증명이나 CSP 장애로 목록이 비면 그 상태가 TTL 동안 고정된다. 자격증명을 고쳐도
 * 화면은 계속 비어 있고, 원인이 캐시라는 사실이 드러나지 않는다 — 실제로 AWS 목록이 비어 보여
 * 코드 결함으로 오진한 적이 있다. 성공한 응답만 담으면 재시도가 바로 반영된다.
 */
@Service
@RequiredArgsConstructor
public class VmOptionsServiceImpl implements VmOptionsService {

    private final VmOptionsQueryService vmOptionsQueryService;

    @Override
    public List<VmOptionProvider> getProviders() {
        return vmOptionsQueryService.listProviders();
    }

    @Override
    @Cacheable(
            value = CacheConfig.VM_OPTIONS_REGIONS,
            key = "T(java.util.Objects).hash(#provider, #credentialId)",
            unless = "#result == null || #result.isEmpty()")
    public List<VmOptionRegion> getRegions(String provider, String credentialId) {
        return vmOptionsQueryService.listRegions(provider, credentialId);
    }

    @Override
    @Cacheable(
            value = CacheConfig.VM_OPTIONS_CONFIG_SCHEMA,
            key = "T(java.util.Objects).hash(#provider, #credentialId, #region)",
            unless = "#result == null || #result.isEmpty()")
    public List<ProviderConfigKey> getConfigSchema(String provider, String credentialId, String region) {
        return vmOptionsQueryService.listConfigSchema(provider, credentialId, region);
    }

    @Override
    @Cacheable(
            value = CacheConfig.VM_OPTIONS_SPECS,
            key = "T(java.util.Objects).hash(#provider, #credentialId, #region, #keyword, #gpuOnly, #limit)",
            unless = "#result == null || #result.isEmpty()")
    public List<VmOptionSpec> getSpecs(
            String provider, String credentialId, String region, String keyword, Boolean gpuOnly, Integer limit) {
        return vmOptionsQueryService.listSpecs(provider, credentialId, region, keyword, gpuOnly, limit);
    }

    @Override
    @Cacheable(
            value = CacheConfig.VM_OPTIONS_IMAGES,
            key =
                    "T(java.util.Objects).hash(#provider, #credentialId, #region, #keyword, #architecture, #owner, #limit)",
            unless = "#result == null || #result.isEmpty()")
    public List<VmOptionImage> getImages(
            String provider,
            String credentialId,
            String region,
            String keyword,
            String architecture,
            String owner,
            Integer limit) {
        return vmOptionsQueryService.listImages(provider, credentialId, region, keyword, architecture, owner, limit);
    }
}
