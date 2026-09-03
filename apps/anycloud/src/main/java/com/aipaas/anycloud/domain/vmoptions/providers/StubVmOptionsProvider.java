package com.aipaas.anycloud.domain.vmoptions.providers;

import com.aipaas.anycloud.domain.provisioning.model.SupportedProvisioningProvider;
import com.aipaas.anycloud.domain.vmoptions.api.VmOptionProvider;
import com.aipaas.anycloud.domain.vmoptions.api.VmOptionRegion;
import java.util.List;
import lombok.RequiredArgsConstructor;

@RequiredArgsConstructor
public class StubVmOptionsProvider extends AbstractVmOptionsProvider {

    private final SupportedProvisioningProvider provider;
    private final String notes;
    private final List<VmOptionRegion> regions;

    @Override
    public SupportedProvisioningProvider getProvider() {
        return provider;
    }

    @Override
    public VmOptionProvider describe() {
        return describe(provider, false, notes);
    }

    @Override
    public List<VmOptionRegion> listRegions() {
        return regions;
    }
}
