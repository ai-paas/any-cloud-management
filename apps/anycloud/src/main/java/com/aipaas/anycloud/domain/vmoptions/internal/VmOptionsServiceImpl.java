package com.aipaas.anycloud.domain.vmoptions.internal;

import com.aipaas.anycloud.configuration.persistence.CacheConfig;
import com.aipaas.anycloud.domain.vmoptions.VmOptionsQueryService;
import com.aipaas.anycloud.domain.vmoptions.VmOptionsService;
import com.aipaas.anycloud.domain.vmoptions.api.VmOptionImage;
import com.aipaas.anycloud.domain.vmoptions.api.VmOptionProvider;
import com.aipaas.anycloud.domain.vmoptions.api.VmOptionRegion;
import com.aipaas.anycloud.domain.vmoptions.api.VmOptionSpec;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;

/**
 * VmOptions API facade. CSP provider 의 외부 metadata API (regions/specs/images) 호출은
 * 매 요청마다 quota / latency 부담이 크므로 Caffeine 캐시로 30분 TTL 적용 .
 * <p>
 * cache key 는 메서드 시그니처 전체 — 같은 provider+region+keyword 조합은 같은 응답.
 * 동일 cluster 생성 폼에서 user 가 region 바꿀 때마다 호출돼도 한 번만 외부로 나간다.
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
    @Cacheable(value = CacheConfig.VM_OPTIONS_REGIONS, key = "T(java.util.Objects).hash(#provider, #credentialId)")
    public List<VmOptionRegion> getRegions(String provider, String credentialId) {
        return vmOptionsQueryService.listRegions(provider, credentialId);
    }

    @Override
    @Cacheable(
            value = CacheConfig.VM_OPTIONS_SPECS,
            key = "T(java.util.Objects).hash(#provider, #credentialId, #region, #keyword, #gpuOnly, #limit)")
    public List<VmOptionSpec> getSpecs(
            String provider, String credentialId, String region, String keyword, Boolean gpuOnly, Integer limit) {
        return vmOptionsQueryService.listSpecs(provider, credentialId, region, keyword, gpuOnly, limit);
    }

    @Override
    @Cacheable(
            value = CacheConfig.VM_OPTIONS_IMAGES,
            key =
                    "T(java.util.Objects).hash(#provider, #credentialId, #region, #keyword, #architecture, #owner, #limit)")
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
