package com.aipaas.anycloud.domain.addon.installer;

import com.aipaas.anycloud.domain.addon.model.AddonType;
import io.aipaas.cluster.agent.runtime.HelmReleaseService;
import org.springframework.stereotype.Component;

/**
 * 모든 미명시 helm chart 의 fallback installer.
 *
 * <p>{@link AddonType#GENERIC} 으로 등록된 addon (catalog 에 있지만 type 매핑 없거나, custom chart)
 * 의 install. base 로직 그대로 — chart spec 만으로 install.
 */
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
