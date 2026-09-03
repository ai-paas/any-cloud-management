package com.aipaas.anycloud.domain.addon.internal;

import static org.assertj.core.api.Assertions.assertThat;

import com.aipaas.anycloud.domain.addon.model.AddonType;
import com.aipaas.anycloud.domain.addon.properties.AddonCatalogProperties.AddonGroupBinding;
import com.aipaas.anycloud.domain.addon.properties.AddonCatalogProperties.AddonOidcGroupSelector;
import com.aipaas.anycloud.domain.addon.properties.AddonCatalogProperties.AddonRbac;
import com.aipaas.anycloud.domain.addon.properties.AddonCatalogProperties.AddonRoleRef;
import com.aipaas.anycloud.domain.addon.properties.AddonCatalogProperties.Entry;
import io.aipaas.cluster.agent.rbac.template.BindingTemplate;
import io.aipaas.cluster.agent.rbac.template.RoleRef;
import java.util.List;
import org.junit.jupiter.api.Test;

class AddonRbacTemplateMapperTest {

    @Test
    void nullRbac_returnsEmpty() {
        Entry entry = baseEntry(null);
        assertThat(AddonRbacTemplateMapper.toBindingTemplates(entry)).isEmpty();
    }

    @Test
    void mapsSingleGroupBinding() {
        AddonGroupBinding gb = new AddonGroupBinding(
                new AddonOidcGroupSelector(List.of("dev-team")),
                List.of(new AddonRoleRef("ClusterRole", "kube-prometheus-stack-view", "ClusterScope", null)));
        Entry entry = baseEntry(new AddonRbac(List.of(gb)));

        List<BindingTemplate> out = AddonRbacTemplateMapper.toBindingTemplates(entry);

        assertThat(out).hasSize(1);
        BindingTemplate t = out.get(0);
        assertThat(t.id()).isEqualTo("addon-monitoring-0");
        assertThat(t.oidcGroupSelector().matchExact()).containsExactly("dev-team");
        assertThat(t.roleRefs()).singleElement().satisfies(r -> {
            assertThat(r.kind()).isEqualTo(RoleRef.Kind.ClusterRole);
            assertThat(r.name()).isEqualTo("kube-prometheus-stack-view");
            assertThat(r.scope()).isEqualTo(RoleRef.Scope.ClusterScope);
        });
        // forClusters 가 empty → 모든 cluster 매칭
        assertThat(t.forClusters().matchLabels()).isEmpty();
    }

    @Test
    void mapsMultipleBindings_assignsIndexedIds() {
        AddonRbac rbac = new AddonRbac(List.of(
                new AddonGroupBinding(
                        new AddonOidcGroupSelector(List.of("dev-team")),
                        List.of(new AddonRoleRef("ClusterRole", "view", null, null))),
                new AddonGroupBinding(
                        new AddonOidcGroupSelector(List.of("ops-team")),
                        List.of(new AddonRoleRef("ClusterRole", "admin", null, null)))));

        List<BindingTemplate> out = AddonRbacTemplateMapper.toBindingTemplates(baseEntry(rbac));

        assertThat(out).extracting(BindingTemplate::id).containsExactly("addon-monitoring-0", "addon-monitoring-1");
    }

    @Test
    void mapsNamespacedRoleRef() {
        AddonRbac rbac = new AddonRbac(List.of(new AddonGroupBinding(
                new AddonOidcGroupSelector(List.of("team-x")),
                List.of(new AddonRoleRef("ClusterRole", "edit", "Namespaced", List.of("ns-a", "ns-b"))))));

        BindingTemplate t =
                AddonRbacTemplateMapper.toBindingTemplates(baseEntry(rbac)).get(0);

        assertThat(t.roleRefs().get(0).scope()).isEqualTo(RoleRef.Scope.Namespaced);
        assertThat(t.roleRefs().get(0).namespaces()).containsExactly("ns-a", "ns-b");
    }

    private static Entry baseEntry(AddonRbac rbac) {
        return new Entry(
                "monitoring",
                AddonType.MONITORING,
                "Monitoring",
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
    }
}
