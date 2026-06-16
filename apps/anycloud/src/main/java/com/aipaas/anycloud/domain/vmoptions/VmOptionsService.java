package com.aipaas.anycloud.domain.vmoptions;

import com.aipaas.anycloud.domain.vmoptions.api.VmOptionImage;
import com.aipaas.anycloud.domain.vmoptions.api.VmOptionProvider;
import com.aipaas.anycloud.domain.vmoptions.api.VmOptionRegion;
import com.aipaas.anycloud.domain.vmoptions.api.VmOptionSpec;
import java.util.List;

public interface VmOptionsService {

    List<VmOptionProvider> getProviders();

    List<VmOptionRegion> getRegions(String provider, String credentialId);

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
