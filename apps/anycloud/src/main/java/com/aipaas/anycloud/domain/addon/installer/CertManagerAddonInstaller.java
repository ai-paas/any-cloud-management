package com.aipaas.anycloud.domain.addon.installer;

import com.aipaas.anycloud.domain.addon.model.AddonType;
import io.aipaas.cluster.agent.runtime.HelmReleaseService;
import org.springframework.stereotype.Component;

/** cert-manager (TLS 자동 발급) installer. . */
@Component
public class CertManagerAddonInstaller extends AbstractHelmAddonInstaller {

    public CertManagerAddonInstaller(HelmReleaseService helmReleaseService) {
        super(helmReleaseService);
    }

    @Override
    public AddonType type() {
        return AddonType.CERT_MANAGER;
    }
}
