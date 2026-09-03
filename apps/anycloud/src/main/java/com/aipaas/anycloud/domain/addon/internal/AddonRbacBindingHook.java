package com.aipaas.anycloud.domain.addon.internal;

import com.aipaas.anycloud.domain.addon.AddonCatalog;
import com.aipaas.anycloud.domain.addon.ClusterAddonEntity;
import com.aipaas.anycloud.domain.addon.properties.AddonCatalogProperties;
import io.aipaas.cluster.agent.rbac.port.BindingApplyClient;
import io.aipaas.cluster.agent.rbac.template.BindingTemplate;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * Addon install/uninstall lifecycle 의 RBAC binding 자동 적용 hook.
 *
 * <p>Catalog 의 {@code rbac.groupBindings} → {@link BindingTemplate} 변환 →
 * starter 의 {@link BindingApplyClient#apply} 호출. uninstall 시 label
 * {@code aipaas.io/addon=<catalogId>} 매칭 binding 일괄 cleanup.
 *
 * <p>{@link BindingApplyClient} bean 부재 (test, starter 미설치) 환경 호환 — {@link ObjectProvider}
 * lazy.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AddonRbacBindingHook {

    private final AddonCatalog catalog;
    private final ObjectProvider<BindingApplyClient> applyClientProvider;

    /** addon install 직후. catalog 의 rbac.groupBindings 가 비어있으면 noop. */
    public void onInstall(ClusterAddonEntity addon) {
        BindingApplyClient client = applyClientProvider.getIfAvailable();
        if (client == null) return;

        AddonCatalogProperties.Entry entry = catalog.find(addon.getCatalogId()).orElse(null);
        if (entry == null || entry.rbac() == null) return;

        List<BindingTemplate> templates = AddonRbacTemplateMapper.toBindingTemplates(entry);
        if (templates.isEmpty()) return;

        String actor = "system:addon:" + entry.id();
        for (BindingTemplate template : templates) {
            for (String group : template.oidcGroupSelector().matchExact()) {
                try {
                    client.apply(addon.getClusterId(), template, group, actor);
                } catch (RuntimeException e) {
                    log.warn(
                            "addon RBAC apply failed cluster={} addon={} template={} group={}: {}",
                            addon.getClusterId(),
                            entry.id(),
                            template.id(),
                            group,
                            e.toString());
                }
            }
        }
    }

    /** addon uninstall 직후. label aipaas.io/addon=<catalogId> 매칭 binding 일괄 cleanup. */
    public void onUninstall(ClusterAddonEntity addon) {
        BindingApplyClient client = applyClientProvider.getIfAvailable();
        if (client == null) return;

        String addonId = addon.getCatalogId();
        if (addonId == null || addonId.isBlank()) return;

        try {
            client.deleteByAddon(addon.getClusterId(), addonId, "system:addon:" + addonId);
        } catch (RuntimeException e) {
            log.warn("addon RBAC cleanup failed cluster={} addon={}: {}", addon.getClusterId(), addonId, e.toString());
        }
    }
}
