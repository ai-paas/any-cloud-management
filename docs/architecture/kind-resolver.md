# KindResolver — Dynamic K8s Kind Metadata + Caffeine Cache

cluster agent 의 `RESOLVE_RESOURCE` RPC 응답을 30분 TTL Caffeine cache 로 정규화합니다. 어떤 cluster 에
어떤 CRD 가 깔려 있든 namespaced / cluster-scoped 를 정확히 분류합니다.

## 1. 캐시 대상 — schema 만, data 는 절대 캐시하지 않습니다

**가장 중요한 의사 결정**: 캐시되는 것은 *kind metadata (schema)* 일 뿐, 실제 resource data 는
한 글자도 cache 하지 않습니다.

| 항목                                                                                 | Cache 됨?   | 이유                                                       |
| ------------------------------------------------------------------------------------ | ----------- | ---------------------------------------------------------- |
| `{plural: "pods", namespaced: true, group: "", version: "v1", shortNames: ["po"]}` | ✅ Yes      | cluster 수명 동안 거의 불변 (CRD 신규 install 외 변경 없음) |
| `GET /v1/clusters/c1/namespaces/default/pods` 응답                                   | ❌ Never    | 매 호출 agent → K8s API 직접. 실시간성 100% 보장         |
| `GET /v1/clusters/c1/.../pods/{podName}/logs`                                        | ❌ Never    | 매 호출 fresh                                              |
| `GET /v1/clusters/c1/operations/{id}` 등 backend 자체 자원                            | ❌ Never    | KindResolver 영역 아님                                     |

→ **사용자 입장**: pod 의 status, deployment 의 replica count, configmap value 등은 항상 최신입니다.
캐시는 "namespaced 인지 cluster-scoped 인지만 빠르게 결정"하는 데에만 쓰입니다.

## 2. 컴포넌트 구성

```
┌────────────────────────────────────────────────────────────────────┐
│ ClusterKubernetesController                                        │
│ @GetMapping("/{kind}")                                              │
│   ↓                                                                 │
│ effectiveNamespace(clusterName, namespace, kind)                    │
│   ↓                                                                 │
│ KindResolver.resolve(clusterName, kind) ───┐                       │
│                                            │ cache hit             │
│                                            ▼                        │
│                              ResolvedResource{namespaced=…}        │
│                                            │                        │
│                                            ├ namespaced=true        │
│                                            │   → return path ns    │
│                                            │   (or null if -/_all) │
│                                            └ namespaced=false       │
│                                                → return null        │
└────────────────────────────────────────────────────────────────────┘

KindResolver cache miss
   │
   ▼
KubeResourceService.resolveResource(cluster, input)  ── agent gRPC ──►  agent process
                                                                            │
   ┌────────────────────────────────────────────────────────────────────────┘
   ▼
RESOLVE_RESOURCE response
   │
   ▼
Caffeine cache.put(key, ResolvedResource)
```

### 2.1 핵심 클래스

| 클래스                                                                | 책임                                                         |
| --------------------------------------------------------------------- | ------------------------------------------------------------ |
| `com.aipaas.anycloud.domain.kube.KindResolver`                       | Interface — `resolve/invalidate/invalidateAll`               |
| `com.aipaas.anycloud.domain.kube.internal.CachedKindResolver`            | Caffeine cache impl. TTL 30분, max 5000 entries              |
| `com.aipaas.anycloud.domain.kube.web.ClusterKubernetesController`          | 모든 K8s list/get/apply/delete endpoint 진입점               |
| `com.aipaas.anycloud.domain.kube.web.AdminKindCacheController`             | Admin flush endpoint                                         |
| `com.aipaas.anycloud.domain.addon.installer.AbstractHelmAddonInstaller` | Addon install 직후 invalidate hook                        |
| `com.aipaas.anycloud.model.enums.K8sKinds`                            | Fallback set (agent unavailable 시)                          |

### 2.2 cache key

```
<clusterName> + "|" + lowercased(input)
```

- 대소문자 무관입니다 — `pods`, `PODS`, `Pods` 는 동일 entry
- shortname / Kind 정규화 — agent 가 `po` → `pods` 로 변환해 같은 ResolvedResource 를 반환합니다.
  (단, cache key 자체는 입력 그대로 lowercased 됩니다 — `po` 와 `pods` 가 별도 entry 입니다. agent 호출만
  중복될 뿐, 결과는 일치하므로 정합성 문제는 없습니다.)
- cluster 별 격리입니다 — `c1|pods` vs `c2|pods` 는 별도 entry

### 2.3 TTL & 용량

| 항목       | 값          | 근거                                                                          |
| ---------- | ----------- | ----------------------------------------------------------------------------- |
| TTL        | 30분        | schema 변동 빈도 ≪ 30분. user-visible staleness 무시 가능                     |
| Max size   | 5,000 entries | cluster 10개 × kind 500개 + 여유. memory ~ 5000 × ~500 bytes = ~2.5 MB    |
| Eviction   | TinyLFU (Caffeine default) | size-based eviction 시 hot key 보존                            |
| Stats      | 활성화      | Micrometer 연동 시 hit ratio 노출 (별도 phase)                                |

## 3. Invalidation 전략

다음 3가지 trigger 가 있습니다.

### 3.1 TTL 자연 만료 — 30분

기본입니다. 운영자가 신경 쓰지 않아도 정합성을 보장합니다. CRD install 직후 첫 30분 내에는 fallback 또는
"이전 결과" 가 살아있을 수 있습니다 (3.2 가 이를 보강합니다).

### 3.2 Addon install hook — `AbstractHelmAddonInstaller.install`

```java
helmReleaseService.install(...);
// 직후
kindResolverProvider.getIfAvailable()?.invalidate(addon.getClusterId());
onAfterInstall(addon);
```

- monitoring stack install → PrometheusRule, ServiceMonitor, Probe, AlertmanagerConfig 등 CRD 가 추가됩니다.
- velero install → Backup, Restore, Schedule, BackupStorageLocation 등 CRD 가 추가됩니다.
- ingress-nginx install → ingressclasses 자체는 표준이지만, 운영자 의도로 추가될 수 있습니다.
- cert-manager install → Certificate, Issuer, ClusterIssuer 등 CRD 가 추가됩니다.

모든 helm-based addon install 후 cache 가 flush 되므로, 다음 호출부터 신규 CRD 를 인식합니다.

### 3.3 Admin endpoint — 즉시 flush

```
POST /v1/admin/clusters/{c}/kind-cache/flush
POST /v1/admin/kind-cache/flush                 # all clusters
```

backend orchestration 우회 채널 (kubectl, ArgoCD, operator's reconciliation) 로 CRD 변경 시 사용합니다.

### 3.4 fallback path

agent 가 unavailable 또는 RESOLVE_RESOURCE 가 throw 하면 hardcoded `K8sKinds.CLUSTER_SCOPED`
로 best-effort 답변합니다.

- `nodes`, `namespaces`, `persistentvolumes`, `storageclasses`, `customresourcedefinitions`
  → `namespaced=false` 입니다.
- 그 외 → `namespaced=true` 로 가정합니다 (safer default — UI 에서 namespace 입력을 받아 호출합니다).

fallback 결과도 cache 됩니다 (TTL 30분). agent 가 복구되면 자연 만료 후 정확한 결과로 교체됩니다.

## 4. 사용자 / 프론트엔드 흐름

### 4.1 list (RESTful path)

```
# namespaced kind
GET /v1/clusters/c1/namespaces/monitoring/pods

# cluster-scoped (kubectl 컨벤션: `-` namespace marker)
GET /v1/clusters/c1/namespaces/-/customresourcedefinitions
GET /v1/clusters/c1/namespaces/-/clusterroles

# all-namespaces (kubectl --all-namespaces 등가)
GET /v1/clusters/c1/namespaces/_all/pods
```

backend 의 `effectiveNamespace` 가 입력을 정규화합니다.

| {namespace} path | kind 의 namespaced | 결과 ns 전달          |
| ---------------- | ------------------ | --------------------- |
| `default`        | true               | `"default"`           |
| `_all` or `-`    | true               | `null` (all)          |
| any              | false              | `null` (path 무시)    |

### 4.2 frontend 가 kind picker 만들기

```js
// 1) cluster 의 kind 목록 조회 (별도 endpoint, 이미 존재)
const kinds = await fetch(`/v1/clusters/${c}/resource-kinds`);
// 응답: [{plural, kind, namespaced, group, version, shortNames}, ...]
// 395 kinds 정도 (cluster + CRD 합)

// 2) 사용자가 kind 선택 → namespaced 여부에 따라 분기
if (selected.namespaced) {
  // namespace 목록 fetch → 사용자가 선택 → list 호출
  list(`/v1/clusters/${c}/namespaces/${chosenNs}/${selected.plural}`);
} else {
  // cluster-scoped → `-` marker 고정
  list(`/v1/clusters/${c}/namespaces/-/${selected.plural}`);
}
```

`resource-kinds` endpoint 도 KindResolver 와 동일한 RESOLVE 결과를 사용하지만, 그 endpoint
자체는 별도 path 입니다. KindResolver 는 controller path 의 `{kind}` 정규화에 특화되어 있습니다.

## 5. 성능 / 정확성

| 시나리오                          | 동작                                                  | 영향                                       |
| --------------------------------- | ----------------------------------------------------- | ------------------------------------------ |
| 첫 호출 (cache miss)              | agent RPC ~50-200ms 추가                              | 동일 cluster + kind 의 다음 30분간 0ms     |
| CRD install 직후 (addon)          | install hook 이 flush → 다음 첫 호출 miss             | 1회 RPC, 그 후 cache                       |
| CRD install 직후 (kubectl 우회)   | TTL 만료까지 stale schema 사용 가능 (최대 30분)        | 운영자가 admin flush 호출 권장             |
| agent 일시 unavailable            | RPC 실패 → fallback set → cache 됨                    | TTL 만료 후 자동 복구                      |
| pod data 자체                     | 캐시 안 함                                            | 모든 변경 즉시 반영                        |
| pageSize 큰 list                  | 캐시 안 함                                            | agent 가 K8s pagination 그대로 위임        |

## 6. 운영 가이드

### 6.1 일반

거의 신경 쓸 것이 없습니다. addon install 은 자동 flush 되고, TTL 30분이면 schema 변동
대부분을 cover 합니다.

### 6.2 트러블슈팅

| 증상                                             | 원인 후보                                                       | 조치                                        |
| ------------------------------------------------ | --------------------------------------------------------------- | ------------------------------------------- |
| CRD install 했는데 ns path 잘못 해석 (404 등)  | TTL 만료 전 stale schema (kubectl/ArgoCD 우회 install)        | `POST /v1/admin/clusters/{c}/kind-cache/flush` |
| agent down 중에도 list 응답에 일관성 떨어짐    | fallback 동작 — 표준 외 cluster-scoped 가 namespaced=true 로 분류 | agent 복구 후 30분 대기 또는 flush       |
| memory 증가 의심                                 | max 5000 entries × ~500 byte = ~2.5 MB cap                     | Caffeine stats endpoint 추가 후 hit ratio 확인 |

### 6.3 메트릭 (미구현)

- `kind_resolver_cache_hits_total` / `misses_total`
- `kind_resolver_cache_evictions_total`
- `kind_resolver_resolve_duration_seconds{outcome=hit|miss|fallback}`

## 7. 보안 / 권한

`RESOLVE_RESOURCE` / `LIST_RESOURCE_KINDS` 는 agent allowlist 의 `commands` 목록에 포함
(`apps/agent/deploy/helm/cluster-agent/values.yaml`,
`apps/anycloud/src/main/resources/agent-chart/values.yaml`).

### Agent ClusterRole RBAC

agent core ClusterRole 의 wildcard read 는 `apiGroups: ["*"], resources: ["*"], verbs: [get, list, watch]` 입니다.

운영 의미는 다음과 같습니다.
- generic resource explorer 가 **모든** GVR (built-in + 임의 CRD) 를 cover 합니다.
- secrets 도 read 가능합니다 — `LIST_RESOURCES` 로 base64 value 가 backend response 에 노출될 가능성이 있습니다.
  compliance 환경에서는 `apps/agent/deploy/helm/cluster-agent/templates/rbac.yaml` 의 wildcard
  rule 을 명시 group list 로 narrow 하거나 backend 단에 `resourceType == "secrets"` redact 처리를 추가합니다.
- mutating verb (`create/update/patch/delete`) 는 **부재합니다** — apply/delete 는 별도 installer SA 입니다.

### KindResolver 자체는 권한 우회가 아닙니다

KindResolver 는 metadata (plural / namespaced) 만 결정합니다. 실제 LIST/GET 은 agent 의 RBAC 를
거치므로 권한 부족 시 응답에 reason code 가 정확히 노출됩니다 — `FORBIDDEN` / `UNSUPPORTED_KIND` /
`RESOURCE_KIND_DENIED` / `AGENT_INACTIVE` 등을 구분합니다.

## 8. 관련 파일

- `apps/anycloud/src/main/java/com/aipaas/anycloud/domain/kube/KindResolver.java`
- `apps/anycloud/src/main/java/com/aipaas/anycloud/domain/kube/internal/CachedKindResolver.java`
- `apps/anycloud/src/main/java/com/aipaas/anycloud/domain/kube/web/ClusterKubernetesController.java`
- `apps/anycloud/src/main/java/com/aipaas/anycloud/domain/kube/web/AdminKindCacheController.java`
- `apps/anycloud/src/main/java/com/aipaas/anycloud/domain/addon/installer/AbstractHelmAddonInstaller.java`
- `apps/anycloud/src/main/java/com/aipaas/anycloud/domain/kube/model/K8sKinds.java` (fallback)
- `libs/cluster-agent-spring-boot-starter/src/main/java/io/aipaas/cluster/agent/runtime/KubeResourceService.java`
- `libs/cluster-agent-spring-boot-starter/src/main/java/io/aipaas/cluster/agent/runtime/ResolvedResource.java`
- `.bruno/Admin/8. Kind Cache Flush (single cluster).bru`
- `.bruno/Admin/9. Kind Cache Flush (all clusters).bru`
- `apps/anycloud/src/test/java/com/aipaas/anycloud/domain/kube/internal/CachedKindResolverTest.java`
