# Dynamic Addon RBAC Binding

addon (helm package) 설치 시 catalog 의 `rbac.groupBindings` spec 을 자동으로
ClusterRoleBinding 으로 변환 — addon 별 RBAC 작성 부담 제거.

## 1. 목적

addon 설치 후 cluster 에 새 CRD 가 생기면 (예: Prometheus / Velero / ArgoCD), impersonation
pass-through 환경에서 사용자가 그 CRD 를 list/get 하려면 ClusterRoleBinding 이 필요. 그런데:

- anycloud 가 catalog 의 권한 정책을 추측하면 잘못된 binding 위험
- 운영자가 cluster × addon 마다 수동 작성하면 addon 설치마다 부담

→ catalog YAML 에 추천 binding 을 선언하면 install 시 자동 적용. uninstall 시 자동 cleanup.

## 2. 구성 요소

| 컴포넌트 | 책임 | 위치 |
|---|---|---|
| **`AddonCatalogProperties.Entry.rbac`** | catalog YAML 의 `rbac.groupBindings` 선언 (OIDC group → ClusterRole 매핑) | `apps/anycloud/.../domain/addon/properties/` |
| **`AddonRbacBindingHook`** | addon install/uninstall lifecycle 의 RBAC binding 자동 apply/cleanup hook | `domain/addon/internal/AddonRbacBindingHook.java` |
| **`AddonRbacTemplateMapper`** | catalog 의 `rbac.groupBindings` → starter 의 `BindingTemplate` 변환 | `domain/addon/internal/AddonRbacTemplateMapper.java` |
| **`AbstractHelmAddonInstaller.onAfterInstall`** | 모든 helm addon installer 의 공통 hook — install 직후 위 mapper 호출 | `domain/addon/installer/` |
| **`BindingApplyClient`** (starter port) | 실제 ClusterRoleBinding apply / label-based cleanup | `libs/cluster-agent-features-spring-boot-starter` (rbac sub-feature) |

## 3. 데이터 흐름

```
사용자: POST /v1/clusters/{id}/addons {catalogId: "monitoring"}
   │
   ▼
AbstractHelmAddonInstaller.install
   │   └── helm install kube-prometheus-stack
   ▼
AbstractHelmAddonInstaller.onAfterInstall (hook)
   │
   ▼
AddonRbacBindingHook.onInstall
   │   └── catalog.find(catalogId) → Entry.rbac
   │   └── AddonRbacTemplateMapper.toBindingTemplates → List<BindingTemplate>
   ▼
BindingApplyClient.apply (starter port)
   │   └── ClusterRoleBinding 생성 + label 'aipaas.io/addon=<catalogId>'
   ▼
완료 — OIDC group 의 사용자가 addon 의 CRD list/get 가능
```

uninstall 시: `AddonRbacBindingHook.onUninstall` 가 label selector
`aipaas.io/addon=<catalogId>` 매칭 binding 일괄 삭제.

## 4. catalog YAML 예시

```yaml
catalogs:
  - id: monitoring
    chart: kube-prometheus-stack
    repo: prometheus-community
    rbac:
      groupBindings:
        - oidcGroupSelector:
            matchExact: [dev-team, qa-team]
          roleRefs:
            - kind: ClusterRole
              name: kube-prometheus-stack-view
              scope: ClusterScope
        - oidcGroupSelector:
            matchExact: [ops-team]
          roleRefs:
            - kind: ClusterRole
              name: kube-prometheus-stack-edit
              scope: ClusterScope
```

- `rbac.groupBindings` 비어있으면 noop (운영자가 직접 binding 작성 가능)
- `kind` / `name` / `scope` 는 starter `BindingTemplate.RoleRef` 구조 그대로

## 5. starter port 없는 환경

`BindingApplyClient` bean 부재 (test, starter 미설치) 시 `ObjectProvider.getIfAvailable()`
로 noop. 즉 cluster-agent-features-rbac sub-feature 가 비활성이면 자동 적용 없이 addon 설치
정상 진행.

## 6. 운영자 override

현재는 catalog YAML 의 정책 그대로 적용. cluster 별 override 가 필요한 경우:
- 별도 binding 을 manual 작성 → label `aipaas.io/managed-by=operator` 추가
- `AddonRbacBindingHook.onUninstall` 는 `aipaas.io/addon=<catalogId>` label 만 매칭하므로 운영자
  override binding 은 영향 X

## 참고

- starter spec: [`starters/cluster-agent-rbac-starter.md`](../starters/cluster-agent-rbac-starter.md)
- impersonation 인증 model: [`k8s-impersonation-auth.md`](./k8s-impersonation-auth.md)
