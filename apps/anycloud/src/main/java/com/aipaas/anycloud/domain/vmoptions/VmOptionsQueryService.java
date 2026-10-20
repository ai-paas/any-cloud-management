package com.aipaas.anycloud.domain.vmoptions;

import com.aipaas.anycloud.domain.vmoptions.api.ProviderConfigKey;
import com.aipaas.anycloud.domain.vmoptions.api.VmOptionImage;
import com.aipaas.anycloud.domain.vmoptions.api.VmOptionProvider;
import com.aipaas.anycloud.domain.vmoptions.api.VmOptionRegion;
import com.aipaas.anycloud.domain.vmoptions.api.VmOptionSpec;
import java.util.List;

/** VM provisioning 시 사용자가 선택 가능한 옵션 (provider / region / spec / image) 조회 facade. */
public interface VmOptionsQueryService {

    List<VmOptionProvider> listProviders();

    List<VmOptionRegion> listRegions(String provider, String credentialId);

    /**
     * config 키 schema 에 선택 가능한 값을 채워 반환.
     *
     * <p>정적 schema 만으로는 포탈이 자유 입력 칸만 제공. IBM zone 처럼 계정마다 다른 값은
     * 사용자가 규칙을 기억해 입력해야 하고, 오타는 프로비저닝 중에 드러남.
     */
    List<ProviderConfigKey> listConfigSchema(String provider, String credentialId, String region);

    boolean isInstanceTypeAvailableInZone(
            String provider, String credentialId, String region, String zone, String instanceType);

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
