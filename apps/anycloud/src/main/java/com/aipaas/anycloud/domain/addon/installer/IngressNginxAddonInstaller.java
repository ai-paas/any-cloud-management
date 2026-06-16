package com.aipaas.anycloud.domain.addon.installer;

import com.aipaas.anycloud.domain.addon.model.AddonType;
import io.aipaas.cluster.agent.runtime.HelmReleaseService;
import org.springframework.stereotype.Component;

/** Ingress-NGINX controller installer. . */
@Component
public class IngressNginxAddonInstaller extends AbstractHelmAddonInstaller {

    public IngressNginxAddonInstaller(HelmReleaseService helmReleaseService) {
        super(helmReleaseService);
    }

    @Override
    public AddonType type() {
        return AddonType.INGRESS_NGINX;
    }
}
