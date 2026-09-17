# cluster-agent RBAC sub-feature

OIDC group → K8s ClusterRoleBinding orchestration. Layer 2 통합 starter
[`cluster-agent-features-spring-boot-starter`](./cluster-agent-features-starter.md) 의
sub-package `io.aipaas.cluster.agent.rbac` 으로 제공. 본 문서는 RBAC sub-feature 의 scope,
책임, 외부 API, SPI 를 정의합니다.

## 1. Layer 와 위치

| Starter | 책임 | 호스트 의존 SPI |
|---|---|---|
| `cluster-agent-spring-boot-starter` (Layer 1) | gRPC reverse-tunnel, agent registration, K8s API / Helm dispatcher | `AgentIdentityStore` |
| `cluster-agent-features-spring-boot-starter` — `observability` (Layer 2) | PromQL / Alertmanager / Grafana 통신, alert silence, rule | `ClusterCatalog` |
| `cluster-agent-features-spring-boot-starter` — `backup` (Layer 2) | etcd / PKI 백업, Velero install, Backup, Restore, Schedule | `BackupHistoryWriter` (선택) |
| **`cluster-agent-features-spring-boot-starter` — `rbac`** (Layer 2) | **OIDC group → ClusterRoleBinding 자동 apply, fleet view, audit** | (선택) `BindingTemplateCatalog` / `BindingAuditSink` |
| 호스트 application (e.g. anycloud) | REST controller, cluster registry, addon catalog 통합 | — |

Layer 2 의 3 starter (observability / backup / rbac) 는 모두 독립. 어느 하나만 import 해도 동작.
모두 cluster-agent-starter (Layer 1) 위에 build.

본 starter 의 활성 조건은 Layer 1 의 `KubeResourceService` bean 존재. ClusterRoleBinding apply 는
agent gRPC 의 `APPLY_MANIFEST` 사용.

## 2. 핵심 가정 — DB-free, K8s + Keycloak 이 truth

본 starter 는 **데이터베이스 없이 동작**.

| 데이터 | 저장 위치 | Truth |
|---|---|---|
| 사용자 identity | Keycloak (OIDC IdP) | Keycloak |
| 그룹 / role membership | Keycloak | Keycloak |
| RBAC 정책 (group → ClusterRole) | K8s 의 ClusterRoleBinding | K8s |
| Addon → ClusterRole mapping | addon helm chart 가 동봉한 ClusterRole | K8s |
| 운영자 catalog (binding 추천) | starter classpath resource (`binding-templates.yaml`) | starter |

이 모델의 결과:
- **single source of truth = K8s** — drift 없음, GitOps 호환
- **외부 host 재배포 가능** — backend RDB 없이 starter 만 import 해도 동작
- **host application 부담 감소** — RBAC entity / table 책임 외

자세한 배경: [`../identity/oidc-binding-multi-idp.md`](../identity/oidc-binding-multi-idp.md),
[`../persistence-layer.md`](../persistence-layer.md) §"DB 가 갖지 않는 책임".

## 3. Scope — in / out

### 3.1 starter 안 (in)

| 영역 | 포함 |
|---|---|
| Template 모델 | `BindingTemplate` (canonical) + `TieredBindingTemplate` (운영자 작성 form, expand 시 BindingTemplate N개로 정규화) |
| Selector | `OidcGroupSelector` (matchExact only), `LabelSelector` (forClusters: matchLabels) |
| K8s RBAC vocab | `TargetSubject`, `RoleRef` (ClusterScope / Namespaced) |
| Template catalog | `BindingTemplateCatalog` SPI + `ClasspathBindingTemplateCatalog` default (binding-templates.yaml) |
| K8s binding apply | `BindingApplyClient` SPI + `AgentBindingApplyClient` default (Layer 1 의 KubeResourceService 위 wrapper) |
| Fleet view | `BindingFleetView` SPI + `SimpleBindingFleetView` default (Caffeine TTL cache) |
| Audit | `BindingAuditSink` SPI + `LoggingBindingAuditSink` default (SLF4J + MDC) |
| Manifest renderer | `BindingManifestRenderer` (ClusterRoleBinding / RoleBinding YAML, `$oidcGroup` placeholder 치환) |
| AutoConfiguration | `ClusterAgentRbacAutoConfiguration` + `ClusterAgentRbacProperties` |

### 3.2 starter 밖 (out — 호스트 책임)

- **사용자 / 그룹 / role membership 영속화** — Keycloak 이 truth. starter 는 mirror 안 함.
- **OIDC JWT 발급 / 검증** — gateway 책임. backend 는 X-User-*/X-Groups 헤더만 trust (이미 anycloud `ImpersonationInterceptor` 가 처리).
- **Addon catalog 와의 통합** — host 의 `AddonRbacBindingHook` 가 `AddonCatalog.find(catalogId).rbac.groupBindings` → mapper → `BindingApplyClient.apply` 호출. 자세한 구조는 [`../identity/dynamic-addon-rbac.md`](../identity/dynamic-addon-rbac.md).
- **REST controller** — host 가 `@RestController` 작성. starter 는 service bean 만 제공.
- **break-glass token 사용 시 별도 audit** — backend 의 `StaticTokenAuthFilter` 에서 처리 (runbook: `../../runbooks/identity/keycloak-outage.md`).
- **UI** — frontend.

### 3.3 cluster-agent 측 책임

starter 는 RPC 클라이언트만 담당. 서버 측 (cluster-agent Go binary) 의 책임:

| 컴포넌트 | 책임 |
|---|---|
| dispatcher `APPLY_MANIFEST` handler | ClusterRoleBinding YAML 을 K8s API 로 apply. allowlist 검사. |
| dispatcher `LIST_RESOURCES` handler | label selector `aipaas.io/managed-by=anycloud` 매칭 binding 조회 (fleet view 용). |
| (향후) informer push | K8s ClusterRoleBinding watch + 변경 시 backend 로 push event — fleet view 즉시 갱신용. |

## 4. YAML schema 예시

### 운영자 작성 form (권장) — tieredRoleRefs

```yaml
# binding-templates.yaml (classpath:)
templates:
  - id: team-x
    oidcGroupSelector:
      matchExact: [team-x]
    tieredRoleRefs:
      tierLabel: anycloud.io/tier         # cluster.labels 의 key
      tiers:
        prod: [{ kind: ClusterRole, name: view,  scope: ClusterScope }]
        stg:  [{ kind: ClusterRole, name: edit,  scope: ClusterScope }]
        dev:  [{ kind: ClusterRole, name: admin, scope: ClusterScope }]
```

starter 가 내부적으로 tier 별 단일 `BindingTemplate` N개로 expand. id = `team-x@prod`,
`team-x@dev`, ...

### Advanced form — forClusters: matchLabels

tier 외 label 매칭 (region, customer 등) 필요 시:

```yaml
templates:
  - id: ops-fleet-admin
    oidcGroupSelector:
      matchExact: [ops-team]
    forClusters:
      matchLabels: {}                       # 모든 cluster
    roleRefs:
      - { kind: ClusterRole, name: cluster-admin, scope: ClusterScope }
```

## 5. Apply 결과의 K8s 객체

```yaml
# starter 가 apply 한 ClusterRoleBinding
apiVersion: rbac.authorization.k8s.io/v1
kind: ClusterRoleBinding
metadata:
  name: aipaas-team-x-prod-team-x
  labels:
    aipaas.io/managed-by: anycloud
    aipaas.io/template: team-x@prod
    aipaas.io/oidc-group: team-x
    # addon hook 으로 생성된 경우 추가:
    # aipaas.io/addon: monitoring
subjects:
  - kind: Group
    name: team-x
    apiGroup: rbac.authorization.k8s.io
roleRef:
  apiGroup: rbac.authorization.k8s.io
  kind: ClusterRole
  name: view
```

label 규약:
- `aipaas.io/managed-by=anycloud` — starter ownership 마킹 (fleet view 의 selector)
- `aipaas.io/template=<templateId>` — template uninstall 시 매칭
- `aipaas.io/oidc-group=<resolvedGroup>` — debug + audit
- `aipaas.io/addon=<addonId>` — addon uninstall 시 매칭 (`AddonRbacBindingHook`)

## 6. AutoConfiguration

```java
@AutoConfiguration
@ConditionalOnClass(KubeResourceService.class)
@EnableConfigurationProperties(ClusterAgentRbacProperties.class)
public class ClusterAgentRbacAutoConfiguration {
    // 4개 default bean — 호스트가 @Bean 재정의 시 그쪽 우선 (@ConditionalOnMissingBean)
}
```

모든 default bean 은 `@ConditionalOnMissingBean` — host 가 자체 impl 제공 시 그게 우선.

## 7. 의존성

| 의존 | 목적 |
|---|---|
| `cluster-agent-spring-boot-starter` (Layer 1) | `KubeResourceService` 사용 |
| `jackson-dataformat-yaml` | `ClasspathBindingTemplateCatalog` 의 binding-templates.yaml 파싱 |
| `Caffeine` | `SimpleBindingFleetView` 의 TTL cache |
| `Spring Boot starter` + validation | 기본 |

## 8. 외부 host 가 자체 application 으로 활용 (예시)

```kotlin
// build.gradle.kts
dependencies {
    implementation("io.aipaas.cluster:cluster-agent-spring-boot-starter:0.1.0")
    implementation("io.aipaas.cluster:cluster-agent-features-spring-boot-starter:0.1.0")
}
```

```yaml
# application.yaml — DB 없이도 동작
cluster-agent:
  jwt:
    secret: ${AGENT_JWT_SECRET}
cluster-rbac:
  templates:
    classpath-resource: binding-templates.yaml
  fleet-view:
    cache-ttl: 30s
  labels:
    managed-by: anycloud
    prefix: aipaas.io
```

→ host 가 자체 RDB 없이도 starter 만으로 RBAC orchestration 가능.

## 9. 관련 자료

- [`./overview.md`](./overview.md) — 전체 starter 모델 + DB-free 가정 매트릭스
- [`../identity/oidc-binding-multi-idp.md`](../identity/oidc-binding-multi-idp.md) — Multi-IdP 설계
- [`../identity/dynamic-addon-rbac.md`](../identity/dynamic-addon-rbac.md) — Addon catalog 기반 ClusterRoleBinding 자동 적용
- [`../../runbooks/identity/keycloak-outage.md`](../../runbooks/identity/keycloak-outage.md) — Keycloak SPOF fallback
