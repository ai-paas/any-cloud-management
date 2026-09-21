package com.aipaas.anycloud.domain.vmoptions;

import com.aipaas.anycloud.domain.vmoptions.api.ProviderConfigKey;
import com.aipaas.anycloud.domain.vmoptions.api.VmOptionImage;
import com.aipaas.anycloud.domain.vmoptions.api.VmOptionProvider;
import com.aipaas.anycloud.domain.vmoptions.api.VmOptionRegion;
import com.aipaas.anycloud.domain.vmoptions.api.VmOptionSpec;
import java.util.List;

public interface VmOptionsService {

    List<VmOptionProvider> getProviders();

    List<VmOptionRegion> getRegions(String provider, String credentialId);

    /** config 키 schema. credentialId 를 주면 계정에서 고를 수 있는 값이 allowedValues 에 채워진다. */
    List<ProviderConfigKey> getConfigSchema(String provider, String credentialId, String region);

    /** 그 존에 이 인스턴스 타입을 띄울 수 있는지. 판단할 수 없으면 true. */
    boolean isInstanceTypeAvailableInZone(
            String provider, String credentialId, String region, String zone, String instanceType);

    List<VmOptionSpec> getSpecs(
            String provider, String credentialId, String region, String keyword, Boolean gpuOnly, Integer limit);

    List<VmOptionImage> getImages(
            String provider,
            String credentialId,
            String region,
            String keyword,
            String architecture,
            String owner,
            Integer limit);
}
