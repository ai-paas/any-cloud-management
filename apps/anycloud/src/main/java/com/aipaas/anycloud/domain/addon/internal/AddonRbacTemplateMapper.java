package com.aipaas.anycloud.domain.addon.internal;

import com.aipaas.anycloud.domain.addon.properties.AddonCatalogProperties.AddonGroupBinding;
import com.aipaas.anycloud.domain.addon.properties.AddonCatalogProperties.AddonOidcGroupSelector;
import com.aipaas.anycloud.domain.addon.properties.AddonCatalogProperties.AddonRoleRef;
import com.aipaas.anycloud.domain.addon.properties.AddonCatalogProperties.Entry;
import io.aipaas.cluster.agent.rbac.template.BindingTemplate;
import io.aipaas.cluster.agent.rbac.template.LabelSelector;
import io.aipaas.cluster.agent.rbac.template.OidcGroupSelector;
import io.aipaas.cluster.agent.rbac.template.RoleRef;
import java.util.ArrayList;
import java.util.List;

/**
 * {@link Entry#rbac()} 의 group binding spec 을 starter 의 {@link BindingTemplate} 으로 변환.
 *
 * <p>{@code AddonInstaller.onAfterInstall} hook 이 본 mapper 로 catalog 의 추천 binding 을
 * starter 의 {@code BindingApplyClient.apply()} 호출 형태로 전환.
 *
 * <p>template id 규약: {@code addon-<entryId>-<index>} — addon uninstall 시 label
 * {@code aipaas.io/addon=<entryId>} 매칭으로 일괄 cleanup.
 */
public final class AddonRbacTemplateMapper {

    private AddonRbacTemplateMapper() {}

    /** addon catalog entry → 모든 group binding 의 BindingTemplate. caller 가 group 매칭 후 apply. */
    public static List<BindingTemplate> toBindingTemplates(Entry entry) {
        if (entry.rbac() == null || entry.rbac().groupBindings() == null) return List.of();

        List<BindingTemplate> out = new ArrayList<>();
        int i = 0;
        for (AddonGroupBinding gb : entry.rbac().groupBindings()) {
            out.add(toBindingTemplate(entry.id(), i++, gb));
        }
        return List.copyOf(out);
    }

    private static BindingTemplate toBindingTemplate(String addonId, int index, AddonGroupBinding gb) {
        return new BindingTemplate(
                "addon-" + addonId + "-" + index,
                new LabelSelector(null),
                toOidcSelector(gb.oidcGroupSelector()),
                null,
                toRoleRefs(gb.roleRefs()),
                addonId); // label aipaas.io/addon=<addonId> 자동 부착 (uninstall cleanup 매칭)
    }

    private static OidcGroupSelector toOidcSelector(AddonOidcGroupSelector src) {
        if (src == null) {
            throw new IllegalArgumentException("addon rbac.oidcGroupSelector 필수");
        }
        return new OidcGroupSelector(OidcGroupSelector.Kind.Group, src.matchExact());
    }

    private static List<RoleRef> toRoleRefs(List<AddonRoleRef> src) {
        if (src == null || src.isEmpty()) return List.of();
        List<RoleRef> out = new ArrayList<>(src.size());
        for (AddonRoleRef r : src) {
            out.add(new RoleRef(parseKind(r.kind()), r.name(), parseScope(r.scope()), r.namespaces()));
        }
        return out;
    }

    private static RoleRef.Kind parseKind(String s) {
        if (s == null) return RoleRef.Kind.ClusterRole;
        return switch (s) {
            case "Role" -> RoleRef.Kind.Role;
            default -> RoleRef.Kind.ClusterRole;
        };
    }

    private static RoleRef.Scope parseScope(String s) {
        if (s == null) return RoleRef.Scope.ClusterScope;
        return switch (s) {
            case "Namespaced" -> RoleRef.Scope.Namespaced;
            default -> RoleRef.Scope.ClusterScope;
        };
    }
}
