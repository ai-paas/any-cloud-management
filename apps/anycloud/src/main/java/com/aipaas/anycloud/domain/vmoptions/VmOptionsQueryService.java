package com.aipaas.anycloud.domain.vmoptions;

import com.aipaas.anycloud.domain.vmoptions.api.VmOptionImage;
import com.aipaas.anycloud.domain.vmoptions.api.VmOptionProvider;
import com.aipaas.anycloud.domain.vmoptions.api.VmOptionRegion;
import com.aipaas.anycloud.domain.vmoptions.api.VmOptionSpec;
import java.util.List;

/**
 * VM provisioning 시 사용자가 선택 가능한 옵션 (provider / region / spec / image) 조회 facade.
 *
 * <p>{@code VmOptionsProvider} (provider 별 구현) 들을 routing 하는 layer. CircuitBreaker /
 * fallback 은 각 provider 의 책임.
 */
public interface VmOptionsQueryService {

    List<VmOptionProvider> listProviders();

    List<VmOptionRegion> listRegions(String provider, String credentialId);

    List<VmOptionSpec> listSpecs(
            String provider, String credentialId, String region, String keyword, Boolean gpuOnly, Integer limit);

    List<VmOptionImage> listImages(
            String provider,
            String credentialId,
            String region,
            String keyword,
            String architecture,
            String owner,
            Integer limit);
}
