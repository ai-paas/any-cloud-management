# K8s 접근 경로

anycloud 가 Kubernetes API 에 도달하는 경로입니다. cluster lifecycle 의 phase 에 따라 둘로 분리됩니다.

| Phase | Path | Entry |
|---|---|---|
| **Bootstrap** (신규 cluster 검증 / kubeconfig 파싱) | fabric8 direct | `AgentBootstrapKubeClient` |
| **Day-2 ops** (list / get / apply / delete / logs / exec) | agent gRPC | `KubeServiceImpl.requireAgent` |

Day-2 path 에서 agent stream 이 없으면 즉시 503 `AGENT_UNAVAILABLE` 을 응답합니다 — fabric8 fallback 이 없습니다.

## 경로 다이어그램

```
        ┌─────────────────────────────────────────────────────┐
        │                anycloud (Spring backend)            │
        │   ┌──────────────────────────────────────────────┐  │
        │   │   KubeServiceImpl  ·  HelmReleaseService     │  │
        │   │   KubeResourceService  ·  lifecycle starter  │  │
        │   └─────────────┬──────────────────────────┬─────┘  │
        └─────────────────┼──────────────────────────┼────────┘
                          │                          │
              Day-2 ops   │                          │  Bootstrap only
              requireAgent│                          │  AgentBootstrapKubeClient
                          ▼                          ▼
        ┌─────────────────────────┐   ┌───────────────────────────┐
        │  AgentSessionRegistry   │   │  fabric8 KubernetesClient │
        │  → gRPC bidi stream     │   │  → kubeconfig per cluster │
        │  → cluster-agent Pod    │   │  → K8s API                │
        │  → K8s API (in-cluster) │   │                           │
        └─────────────────────────┘   └───────────────────────────┘
                  │                              │
                  │ session 없음                  │
                  ▼                              │
            503 AGENT_UNAVAILABLE                ▼
                                          신규 cluster 검증 /
                                          kubeconfig 파싱 / connectivity
```

## Day-2 path — agent-only

`KubeServiceImpl.requireAgent(clusterName)` 가 `AgentSessionRegistry.find(clusterName)` 로 활성 stream 을
확인합니다. 없으면 `KubeRoutingException` → controller advice 가 503 `AGENT_UNAVAILABLE` 을 응답합니다.

| 항목 | 동작 |
|---|---|
| 인증 | gRPC mTLS 또는 Bearer (identity token) 입니다. |
| 보안 표면 | bootstrap token 1회 → identity token 입니다. kubeconfig 를 보관하지 않습니다. |
| 네트워크 | reverse tunnel (cluster → backend) 입니다. |
| RBAC 강제 | agent core ClusterRole + allowlist ConfigMap 입니다. |
| Audit | agent 가 in-cluster event 를 발행할 수 있습니다. |

지원 ops 는 다음과 같습니다.

| 명령 | 매핑 |
|---|---|
| pod logs | `GET_LOG` |
| get single | `GET_RESOURCE` |
| list (paginated) | `LIST_RESOURCES` |
| delete | `DELETE_RESOURCE` |
| apply manifest | `APPLY_MANIFEST` |
| kind metadata resolve | `RESOLVE_RESOURCE` + Caffeine cache (`kind-resolver.md`) |
| helm install / upgrade / uninstall | `INSTALL_ADDON` / `UPGRADE_ADDON` / `UNINSTALL_ADDON` |
| pod exec | WebSocket bridge (별도 path) |

미지원 (현재 protocol 미확장) 항목은 다음과 같습니다.

| 명령 | 비고 |
|---|---|
| watch (이벤트 stream) | 별 protocol 확장이 필요합니다. |
| port-forward | 별 protocol 확장이 필요합니다. |

## Bootstrap path — fabric8 direct

신규 cluster 등록 시 운영자가 제공한 kubeconfig 로 직접 K8s API 에 접근합니다. agent 가 아직 없는 상태에서
초기 검증 / agent 설치용 manifest apply 등에 사용합니다. `AgentBootstrapKubeClient.execute(cluster, lambda)`
가 single entry 입니다.

사용 site 는 다음과 같습니다.
- `VmClusterRegistrationServiceImpl` — 신규 cluster 의 K8s API 도달 검증입니다.
- `KubeServiceImpl.applyManifest` (BOOTSTRAP 전용) — agent 설치 manifest 의 server-side apply 입니다.
- `KubeconfigParser` — kubeconfig YAML → cluster 인증 필드 추출입니다.

day-2 ops 에서는 호출되지 않습니다.

## RBAC

agent core ClusterRole 이 wildcard `get/list/watch` + `impersonate` verb 를 보유합니다. 사용자 RBAC pass-through
가 필요하면 [`k8s-impersonation-auth.md`](./identity/k8s-impersonation-auth.md) 의 toggle 을 활성화합니다.

## 운영 가시화

503 `AGENT_UNAVAILABLE` 발생률은 cluster 별로 추적할 수 있습니다. starter 의 `KubeResourceService` 가
Micrometer Counter 를 노출합니다.

```promql
sum(rate(cluster_agent_command_failure_total{error_code="NO_ACTIVE_AGENT"}[5m])) by (cluster)
```

## 모듈 책임

```
anycloud (Spring backend)
└── domain/kube/
    ├── KubeService                  # public API (interface)
    └── internal/
        ├── KubeServiceImpl          # day-2 entry, requireAgent → AgentSessionRegistry
        ├── CachedKindResolver       # kind metadata cache (Caffeine)
        ├── K8sResponseSanitizer
        └── KubeErrorClassifier
domain/cluster/
    ├── AgentBootstrapKubeClient     # bootstrap entry, fabric8 wrapper
    └── kubeconfig/KubeconfigParser  # kubeconfig YAML → 인증 필드 (fabric8 model)
configuration/persistence/
    └── KubernetesClientFactory      # fabric8 client builder (bootstrap 전용)

libs/cluster-agent-spring-boot-starter (transport)
└── runtime/
    ├── AgentSessionRegistry         # 활성 stream + pending future
    ├── KubeResourceService          # day-2 ops gRPC client
    └── HelmReleaseService           # helm ops gRPC client
```
