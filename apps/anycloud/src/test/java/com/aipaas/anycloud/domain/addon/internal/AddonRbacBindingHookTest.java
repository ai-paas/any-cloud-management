package com.aipaas.anycloud.domain.addon.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.aipaas.anycloud.domain.addon.AddonCatalog;
import com.aipaas.anycloud.domain.addon.ClusterAddonEntity;
import com.aipaas.anycloud.domain.addon.model.AddonType;
import com.aipaas.anycloud.domain.addon.properties.AddonCatalogProperties.AddonGroupBinding;
import com.aipaas.anycloud.domain.addon.properties.AddonCatalogProperties.AddonOidcGroupSelector;
import com.aipaas.anycloud.domain.addon.properties.AddonCatalogProperties.AddonRbac;
import com.aipaas.anycloud.domain.addon.properties.AddonCatalogProperties.AddonRoleRef;
import com.aipaas.anycloud.domain.addon.properties.AddonCatalogProperties.Entry;
import io.aipaas.cluster.agent.rbac.port.BindingApplyClient;
import io.aipaas.cluster.agent.rbac.template.BindingTemplate;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;

class AddonRbacBindingHookTest {

    @SuppressWarnings("unchecked")
    private static ObjectProvider<BindingApplyClient> provider(BindingApplyClient client) {
        ObjectProvider<BindingApplyClient> p = mock(ObjectProvider.class);
        when(p.getIfAvailable()).thenReturn(client);
        return p;
    }

    private static AddonCatalog catalogWith(String addonId, AddonRbac rbac) {
        Entry entry = new Entry(
                addonId,
                AddonType.MONITORING,
                "mon",
                null,
                "prometheus-community",
                "kube-prometheus-stack",
                null,
                "monitoring",
                "monitoring",
                null,
                null,
                null,
                null,
                rbac);
        AddonCatalog mock = mock(AddonCatalog.class);
        when(mock.find(eq(addonId))).thenReturn(Optional.of(entry));
        when(mock.find(org.mockito.ArgumentMatchers.argThat(s -> !addonId.equals(s))))
                .thenReturn(Optional.empty());
        return mock;
    }

    private static ClusterAddonEntity addon(String addonId) {
        ClusterAddonEntity a = new ClusterAddonEntity();
        a.setClusterId("c1");
        a.setCatalogId(addonId);
        return a;
    }

    @Test
    void onInstall_appliesAllGroupBindings() {
        AddonRbac rbac = new AddonRbac(List.of(new AddonGroupBinding(
                new AddonOidcGroupSelector(List.of("dev-team", "qa-team")),
                List.of(new AddonRoleRef("ClusterRole", "view", "ClusterScope", null)))));
        BindingApplyClient client = mock(BindingApplyClient.class);

        new AddonRbacBindingHook(catalogWith("monitoring", rbac), provider(client)).onInstall(addon("monitoring"));

        ArgumentCaptor<BindingTemplate> tplCaptor = ArgumentCaptor.forClass(BindingTemplate.class);
        ArgumentCaptor<String> groupCaptor = ArgumentCaptor.forClass(String.class);
        verify(client, times(2))
                .apply(eq("c1"), tplCaptor.capture(), groupCaptor.capture(), eq("system:addon:monitoring"));

        assertThat(groupCaptor.getAllValues()).containsExactly("dev-team", "qa-team");
        assertThat(tplCaptor.getValue().addonId()).isEqualTo("monitoring");
    }

    @Test
    void onInstall_noRbac_noOps() {
        BindingApplyClient client = mock(BindingApplyClient.class);

        new AddonRbacBindingHook(catalogWith("monitoring", null), provider(client)).onInstall(addon("monitoring"));

        verify(client, never()).apply(any(), any(), any(), any());
    }

    @Test
    void onInstall_clientUnavailable_noOps() {
        AddonRbac rbac = new AddonRbac(List.of(new AddonGroupBinding(
                new AddonOidcGroupSelector(List.of("dev-team")),
                List.of(new AddonRoleRef("ClusterRole", "view", null, null)))));
        @SuppressWarnings("unchecked")
        ObjectProvider<BindingApplyClient> emptyProvider = mock(ObjectProvider.class);
        when(emptyProvider.getIfAvailable()).thenReturn(null);

        // 단순히 NPE 안 던지면 OK
        new AddonRbacBindingHook(catalogWith("monitoring", rbac), emptyProvider).onInstall(addon("monitoring"));
    }

    @Test
    void onUninstall_deletesByAddonLabel() {
        BindingApplyClient client = mock(BindingApplyClient.class);

        new AddonRbacBindingHook(catalogWith("monitoring", null), provider(client)).onUninstall(addon("monitoring"));

        verify(client).deleteByAddon("c1", "monitoring", "system:addon:monitoring");
    }

    @Test
    void onUninstall_nullCatalogId_noOps() {
        BindingApplyClient client = mock(BindingApplyClient.class);
        ClusterAddonEntity addon = new ClusterAddonEntity();
        addon.setClusterId("c1");
        // catalogId null

        new AddonRbacBindingHook(catalogWith("monitoring", null), provider(client)).onUninstall(addon);

        verify(client, never()).deleteByAddon(any(), any(), any());
    }
}
