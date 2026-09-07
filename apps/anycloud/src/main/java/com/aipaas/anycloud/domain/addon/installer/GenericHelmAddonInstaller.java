package com.aipaas.anycloud.domain.addon.installer;

import com.aipaas.anycloud.domain.addon.model.AddonType;
import io.aipaas.cluster.agent.runtime.HelmReleaseService;
import org.springframework.stereotype.Component;

/** 모든 미명시 helm chart 의 fallback installer. */
@Component
public class GenericHelmAddonInstaller extends AbstractHelmAddonInstaller {

    public GenericHelmAddonInstaller(HelmReleaseService helmReleaseService) {
        super(helmReleaseService);
    }

    @Override
    public AddonType type() {
        return AddonType.GENERIC;
    }
}
