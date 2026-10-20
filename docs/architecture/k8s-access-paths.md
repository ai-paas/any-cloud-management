# K8s 접근 경로

anycloud 는 target cluster 의 Kubernetes API 를 **직접 호출하지 않습니다.** 모든 호출이 cluster 안에서 도는 cluster-agent 를 거칩니다.

backend 는 cluster 자격증명을 보관하지 않습니다. `ClusterEntity` 에서 `server_ca`, `client_ca`, `client_key`, `client_token` 컬럼을 모두 걷어냈고, fabric8 의존성도 제거했습니다. 따라서 agent 를 우회할 수단 자체가 없습니다.

## 경로

```
┌──────────────────────────────────────────────┐
│           anycloud (Spring backend)          │
│   KubeServiceImpl, HelmReleaseService        │
│   KubeResourceService (starter)              │
└───────────────────────┬──────────────────────┘
                        │ requireAgent
                        ▼
              ┌──────────────────────┐
              │ AgentSessionRegistry │
              └───────────┬──────────┘
                          │ gRPC bidi stream (cluster → backend)
                          ▼
              ┌──────────────────────┐
              │  cluster-agent Pod   │
              │  → K8s API           │
              └──────────────────────┘

   활성 stream 이 없으면 → 503 AGENT_UNAVAILABLE
```

연결 방향이 cluster 에서 backend 로 향하는 reverse tunnel 입니다. backend 가 cluster 의 API server 에 접근할 필요가 없어, cluster 쪽에 inbound 를 열지 않아도 됩니다.

## 진입점

`KubeServiceImpl.requireAgent(clusterName)` 가 `AgentSessionRegistry.find(clusterName)` 로 활성 stream 을 확인합니다. 없으면 `KubeRoutingException` 이 올라가고 controller advice 가 503 `AGENT_UNAVAILABLE` 로 변환합니다.

실패를 빈 결과로 돌려주지 않습니다. `data: []` 는 "조회 결과 없음" 과 구분되지 않아 200 으로 나가기 때문입니다.

| 항목 | 내용 |
|---|---|
| 인증 | identity token (Bearer). 60일 TTL 이고 cluster 의 K8s Secret 에 보관합니다 |
| 네트워크 | reverse tunnel (cluster → backend) |
| RBAC | agent core ClusterRole 과 allowlist ConfigMap |
| 자격증명 보관 | 없음. backend 는 kubeconfig 를 저장하지 않습니다 |

## 지원 명령

전체 목록은 `libs/cluster-agent-spring-boot-starter/src/main/proto/agent/v1/runtime.proto` 의 `CommandType` 이 단일 출처입니다.

| 기능 | 명령 |
|---|---|
| 리소스 조회 | `GET_RESOURCE`, `LIST_RESOURCES`, `LIST_RESOURCE_KINDS` |
| 리소스 변경 | `APPLY_MANIFEST`, `DELETE_RESOURCE`, `SCALE_DEPLOYMENT` |
| Pod 로그 | `GET_LOG` |
| kind metadata | `RESOLVE_RESOURCE` ([`kind-resolver.md`](./kind-resolver.md) 의 Caffeine cache 와 함께 동작) |
| Helm | `INSTALL_ADDON`, `UPGRADE_ADDON`, `UNINSTALL_ADDON`, `LIST_HELM_RELEASES`, `GET_HELM_RELEASE_STATUS`, `GET_HELM_RELEASE_HISTORY`, `ROLLBACK_HELM_RELEASE` |
| 정책 | `GET_AGENT_CONFIG`, `APPLY_AGENT_CONFIG`, `ENSURE_AGENT_CONFIG_ANNOTATIONS` |
| kubeconfig 발급 | `GENERATE_KUBECONFIG` |
| 디버그 | `CREATE_NODE_DEBUG_POD` |

Pod exec 은 별도 WebSocket bridge 를 씁니다.

`watch` 와 `port-forward` 는 지원하지 않습니다. 양쪽 모두 protocol 확장이 필요합니다.

## agent 설치는 사용자가 합니다

agent 가 없는 cluster 에는 backend 가 아무것도 적용할 수 없습니다. cluster 를 등록하면 응답에 설치 명령이 담기고, 운영자가 자기 kubectl context 에서 실행합니다.

```bash
curl -sS {baseUrl}/v1/clusters/{name}/agent-manifest.yaml | kubectl apply -f -
```

agent 가 떠서 backend 에 붙으면 그때부터 day-2 작업이 가능합니다. 이미 등록된 cluster 의 agent 를 다시 배포하려면 `POST /v1/admin/clusters/{name}/agent/reinstall` 을 쓰는데, 이 호출도 agent gRPC 를 타므로 agent 가 완전히 죽은 상태에서는 위 manifest 를 직접 적용해야 합니다.

## RBAC

agent core ClusterRole 이 wildcard `get/list/watch` 와 `impersonate` verb 를 가집니다. 사용자 단위 RBAC 로 좁히려면 [`k8s-impersonation-auth.md`](./identity/k8s-impersonation-auth.md) 의 toggle 을 켜세요.

## 운영 가시화

503 `AGENT_UNAVAILABLE` 발생률은 cluster 별로 추적할 수 있습니다.

```promql
sum(rate(cluster_agent_command_failure_total{error_code="NO_ACTIVE_AGENT"}[5m])) by (cluster)
```

## 모듈 책임

```
anycloud (Spring backend)
└── domain/kube/
    ├── KubeService                  # public API (interface)
    └── internal/
        ├── KubeServiceImpl          # 진입점. requireAgent → AgentSessionRegistry
        ├── CachedKindResolver       # kind metadata cache (Caffeine)
        ├── K8sResponseSanitizer     # managedFields 제거, Secret 값 가림
        └── KubeErrorClassifier      # 실패 원인 분류

libs/cluster-agent-spring-boot-starter (transport)
└── runtime/
    ├── AgentSessionRegistry         # 활성 stream + 대기 중인 future
    ├── KubeResourceService          # K8s ops gRPC client
    └── HelmReleaseService           # helm ops gRPC client
```
