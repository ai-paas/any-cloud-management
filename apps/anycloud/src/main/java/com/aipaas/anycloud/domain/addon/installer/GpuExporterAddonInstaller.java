package com.aipaas.anycloud.domain.addon.installer;

import com.aipaas.anycloud.domain.addon.model.AddonType;
import io.aipaas.cluster.agent.runtime.HelmReleaseService;
import org.springframework.stereotype.Component;

/** NVIDIA DCGM exporter (GPU node metric) installer. . */
@Component
public class GpuExporterAddonInstaller extends AbstractHelmAddonInstaller {

    public GpuExporterAddonInstaller(HelmReleaseService helmReleaseService) {
        super(helmReleaseService);
    }

    @Override
    public AddonType type() {
        return AddonType.GPU_EXPORTER;
    }
}
