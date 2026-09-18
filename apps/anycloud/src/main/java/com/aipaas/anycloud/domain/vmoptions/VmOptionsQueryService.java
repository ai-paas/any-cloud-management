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
     * config 키 schema 에 실제 고를 수 있는 값을 채워 반환한다.
     *
     * <p>정적 schema 만으로는 포탈이 자유 입력 칸밖에 못 만든다. IBM zone 처럼 계정마다 다른 값은
     * 사용자가 규칙을 외워 타이핑해야 하고, 틀리면 프로비저닝 도중에야 알게 된다.
     */
    List<ProviderConfigKey> listConfigSchema(String provider, String credentialId, String region);

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
