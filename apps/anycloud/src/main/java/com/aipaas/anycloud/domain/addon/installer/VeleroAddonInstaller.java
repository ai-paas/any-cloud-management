package com.aipaas.anycloud.domain.addon.installer;

import com.aipaas.anycloud.domain.addon.ClusterAddonEntity;
import com.aipaas.anycloud.domain.addon.model.AddonType;
import io.aipaas.cluster.agent.backup.velero.BackupPolicyInstaller;
import io.aipaas.cluster.agent.runtime.HelmReleaseService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/** Velero backup stack installer. */
@Slf4j
@Component
public class VeleroAddonInstaller extends AbstractHelmAddonInstaller {

    private final ObjectProvider<BackupPolicyInstaller> backupPolicyInstallerProvider;

    public VeleroAddonInstaller(
            HelmReleaseService helmReleaseService,
            ObjectProvider<BackupPolicyInstaller> backupPolicyInstallerProvider) {
        super(helmReleaseService);
        this.backupPolicyInstallerProvider = backupPolicyInstallerProvider;
    }

    @Override
    public AddonType type() {
        return AddonType.VELERO;
    }

    @Override
    protected void onAfterInstall(ClusterAddonEntity addon) {
        BackupPolicyInstaller installer = backupPolicyInstallerProvider.getIfAvailable();
        if (installer == null) {
            log.info(
                    "VeleroAddonInstaller: BackupPolicyInstaller bean 부재 — default policy install skip " + "cluster={}",
                    addon.getClusterId());
            return;
        }
        try {
            installer.installAll(addon.getClusterId(), addon.getNamespace());
            log.info(
                    "VeleroAddonInstaller: default BackupPolicy 카탈로그 installed cluster={} namespace={}",
                    addon.getClusterId(),
                    addon.getNamespace());
        } catch (RuntimeException e) {
            // post-install hook 실패가 helm release 자체를 FAILED 로 전환시키지 않음 — log + warn.
            // release 는 정상 deployed, default policy 만 누락. 운영자가 수동 또는 retry 가능.
            log.warn(
                    "VeleroAddonInstaller: default BackupPolicy install failed (non-fatal) cluster={}: {}",
                    addon.getClusterId(),
                    e.toString());
        }
    }
}
