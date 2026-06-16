package com.aipaas.anycloud.domain.addon.properties;

import com.aipaas.anycloud.domain.addon.model.AddonType;
import java.util.Collections;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Addon catalog YAML binding — addons.yaml 의 declarative spec.
 *
 * <p>catalog-driven install 의 source-of-truth. 새 addon 추가는
 * YAML entry 1개만 추가 — 코드 변경 불필요.
 *
 * <p>spring.config.import 또는 classpath:config/addons.yaml 자동 load.
 */
@ConfigurationProperties(prefix = "addon-catalog")
public record AddonCatalogProperties(boolean enabled, List<Entry> addons) {

    public AddonCatalogProperties {
        addons = addons == null ? Collections.emptyList() : List.copyOf(addons);
    }

    /**
     * Single catalog entry.
     *
     * @param id                   URL-safe kebab. {@code AddonSpec.catalogId} 의 값.
     * @param type                 installer strategy dispatch key.
     * @param displayName          UI 표시명.
     * @param description          짧은 설명.
     * @param chartRepo            repo alias (예: prometheus-community).
     * @param chartName            chart name.
     * @param chartVersion         default version. caller override 가능.
     * @param namespace            default 설치 namespace.
     * @param releaseName          default helm release name.
     * @param repoUrl              alias resolve 우회용 명시 URL.
     * @param defaultValuesYaml    권장 values JSON/YAML. caller override 가능.
     * @param requiredCapabilities 설치 prerequisite (예: gpu).
     * @param tags                 UI filter 용도.
     * @param rbac — OIDC group → addon ClusterRole 매핑.
     *                             활성 + OidcGroupBinding operator 배포 시 활용. null 가능.
     */
    public record Entry(
            String id,
            AddonType type,
            String displayName,
            String description,
            String chartRepo,
            String chartName,
            String chartVersion,
            String namespace,
            String releaseName,
            String repoUrl,
            String defaultValuesYaml,
            List<String> requiredCapabilities,
            List<String> tags,
            AddonRbac rbac) {

        public Entry {
            requiredCapabilities =
                    requiredCapabilities == null ? Collections.emptyList() : List.copyOf(requiredCapabilities);
            tags = tags == null ? Collections.emptyList() : List.copyOf(tags);
            // rbac null 허용 — addon 의 OIDC group binding 정책 없음 = nop
        }
    }

    /**
     * Addon 별 OIDC group ↔ ClusterRole 매핑. AddonInstaller.onAfterInstall 가
     * 본 spec 을 읽어 OidcGroupBinding CR 빌드. operator 가 reconcile → ClusterRoleBinding apply.
     *
     * @param groupBindings 본 addon 의 group binding 목록. 빈 list 면 자동 binding 안 함.
     */
    public record AddonRbac(List<AddonGroupBinding> groupBindings) {
        public AddonRbac {
            groupBindings = groupBindings == null ? Collections.emptyList() : List.copyOf(groupBindings);
        }
    }

    /**
     * 단일 group selector → 다중 roleRefs.
     *
     * @param oidcGroupSelector 매칭할 OIDC group set
     * @param roleRefs          매칭된 group 에 부여할 ClusterRole/Role list
     */
    public record AddonGroupBinding(AddonOidcGroupSelector oidcGroupSelector, List<AddonRoleRef> roleRefs) {
        public AddonGroupBinding {
            roleRefs = roleRefs == null ? Collections.emptyList() : List.copyOf(roleRefs);
        }
    }

    /**
     * OIDC group 매칭 정책. matchExact (정확 일치) only. dynamic team naming 은 Keycloak group
     * hierarchy 또는 group attribute 활용을 권장 (regex tier-2 폐기 결정, 참조:
     * {@code docs/architecture/design/oidc-binding-multi-idp.md}).
     */
    public record AddonOidcGroupSelector(List<String> matchExact) {
        public AddonOidcGroupSelector {
            matchExact = matchExact == null ? Collections.emptyList() : List.copyOf(matchExact);
        }
    }

    /**
     * ClusterRole / Role reference. OidcGroupBinding operator 의 RoleRef 와 구조 동형.
     *
     * @param kind       ClusterRole | Role
     * @param name       역할 이름 (예: kube-prometheus-stack-view)
     * @param scope      ClusterScope | Namespaced
     * @param namespaces Namespaced 일 때 적용 대상 namespace list. 비어있으면 ClusterScope 강제
     */
    public record AddonRoleRef(String kind, String name, String scope, List<String> namespaces) {
        public AddonRoleRef {
            namespaces = namespaces == null ? Collections.emptyList() : List.copyOf(namespaces);
        }
    }
}
