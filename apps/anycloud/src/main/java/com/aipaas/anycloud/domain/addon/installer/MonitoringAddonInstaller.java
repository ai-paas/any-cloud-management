package com.aipaas.anycloud.domain.addon.installer;

import com.aipaas.anycloud.domain.addon.ClusterAddonEntity;
import com.aipaas.anycloud.domain.addon.model.AddonType;
import io.aipaas.cluster.agent.observability.alerts.AlertRuleInstaller;
import io.aipaas.cluster.agent.observability.stack.DefaultDashboardImporter;
import io.aipaas.cluster.agent.runtime.HelmReleaseService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * Monitoring stack (kube-prometheus-stack) installer.
 *
 * <p>post-install hook
 * <ol>
 *   <li>{@link DefaultDashboardImporter} 가 cluster-overview + GPU dashboard import.</li>
 *   <li>{@link AlertRuleInstaller#installAll} 로 default PrometheusRule 카탈로그 install.</li>
 * </ol>
 *
 * <p>kube-prometheus CRD (PrometheusRule, ServiceMonitor, Probe,
 * AlertmanagerConfig 등) 신규 등록 직후 KindResolver cache flush — {@link AbstractHelmAddonInstaller#install}
 * 가 모든 addon 에 대해 공통으로 invalidate 처리하므로 별도 override 불필요.
 *
 * <p>두 starter bean 모두 optional — 부재 시 skip (release 자체는 정상 deployed).
 */
@Slf4j
@Component
public class MonitoringAddonInstaller extends AbstractHelmAddonInstaller {

    private final ObjectProvider<DefaultDashboardImporter> dashboardImporterProvider;
    private final ObjectProvider<AlertRuleInstaller> alertRuleInstallerProvider;

    public MonitoringAddonInstaller(
            HelmReleaseService helmReleaseService,
            ObjectProvider<DefaultDashboardImporter> dashboardImporterProvider,
            ObjectProvider<AlertRuleInstaller> alertRuleInstallerProvider) {
        super(helmReleaseService);
        this.dashboardImporterProvider = dashboardImporterProvider;
        this.alertRuleInstallerProvider = alertRuleInstallerProvider;
    }

    @Override
    public AddonType type() {
        return AddonType.MONITORING;
    }

    @Override
    protected void onAfterInstall(ClusterAddonEntity addon) {
        importDashboards(addon);
        installDefaultAlertRules(addon);
    }

    private void importDashboards(ClusterAddonEntity addon) {
        DefaultDashboardImporter importer = dashboardImporterProvider.getIfAvailable();
        if (importer == null) {
            log.debug("MonitoringAddonInstaller: DefaultDashboardImporter 부재 — dashboard import skip");
            return;
        }
        try {
            importer.importClusterOverview(addon.getClusterId());
        } catch (RuntimeException e) {
            log.warn(
                    "MonitoringAddonInstaller: cluster-overview dashboard import failed (non-fatal) "
                            + "cluster={}: {}",
                    addon.getClusterId(),
                    e.toString());
        }
        // GPU dashboard 는 GPU 노드 보유 cluster 에만 의미 있음 — importer 측이 자체 detect 가정.
        try {
            importer.importGpuOverview(addon.getClusterId());
        } catch (RuntimeException e) {
            log.debug(
                    "MonitoringAddonInstaller: GPU dashboard import skip/fail cluster={}: {}",
                    addon.getClusterId(),
                    e.toString());
        }
    }

    private void installDefaultAlertRules(ClusterAddonEntity addon) {
        AlertRuleInstaller installer = alertRuleInstallerProvider.getIfAvailable();
        if (installer == null) {
            log.debug("MonitoringAddonInstaller: AlertRuleInstaller 부재 — default rule install skip");
            return;
        }
        try {
            installer.installAll(addon.getClusterId(), addon.getNamespace(), addon.getReleaseName(), null);
            log.info(
                    "MonitoringAddonInstaller: default PrometheusRule 카탈로그 installed cluster={}", addon.getClusterId());
        } catch (RuntimeException e) {
            log.warn(
                    "MonitoringAddonInstaller: default rule install failed (non-fatal) cluster={}: {}",
                    addon.getClusterId(),
                    e.toString());
        }
    }
}
