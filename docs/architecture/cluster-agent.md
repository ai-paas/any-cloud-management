# Cluster Agent — Architecture

> **audience**: 시스템 전체의 runtime topology / 인증 / state machine 을 이해하려는 합류자. <br>
> starter module 자체 사용법 (의존성 / autoconfigure bean / properties) 은
> [`starters/cluster-agent-starter.md`](./starters/cluster-agent-starter.md) 참조.

Backend ↔ Kubernetes cluster 통신을 **agent 가 클러스터 안에서 outbound gRPC 로 backend 에 연결**하는
reverse-tunnel 모델로 처리합니다.

특성은 다음과 같습니다.
- Cluster 가 outbound 만 가능해도 동작합니다 (방화벽 친화적입니다).
- Agent 가 in-cluster 이므로 RBAC 만으로 충분합니다 — kubeconfig DB 보관이 불필요합니다.
- mTLS 자동 갱신, allowlist 기반 명령 검증을 수행합니다.

## Topology

```
┌────────────┐                    ┌──────────────────────────────────┐
│   User     │ ─── REST API ───▶  │       Backend (Spring)           │
└────────────┘                    │  ┌──────────┐  ┌──────────────┐  │
                                  │  │ REST API │  │ gRPC Server  │  │
                                  │  └──────────┘  └──────────────┘  │
                                  │       │              ▲           │
                                  │       ▼              │           │
                                  │  ┌──────────────────────────┐    │
                                  │  │  RabbitMQ (workflow)      │   │
                                  │  │   (cluster.* exchange,    │   │
                                  │  │    future async saga)     │   │
                                  │  └──────────────────────────┘    │
                                  └──────────────────────────────────┘
                                              ▲
                                              │ Reverse gRPC stream
                                              │ (mTLS or bearer JWT)
                                              │
                            ┌──────────────────────────────────────┐
                            │       Kubernetes Cluster              │
                            │  ┌────────────────────────────────┐   │
                            │  │       Cluster Agent Pod        │   │
                            │  │  ┌────────┐  ┌──────────────┐  │   │
                            │  │  │ Core   │  │ Controller   │  │   │
                            │  │  │ events │  │ helm/kubectl │  │   │
                            │  │  │ watch  │  │              │  │   │
                            │  │  └────────┘  └──────────────┘  │   │
                            │  └────────────────────────────────┘   │
                            │  (RBAC, allowlist ConfigMap, Secret)  │
                            └──────────────────────────────────────┘
```

## Backend session registry

Backend 가 활성 agent gRPC bidi stream 을 **process-local in-memory** 로 추적합니다.
`AgentSessionRegistry` (cluster-agent-spring-boot-starter) 가 단일 source 입니다.

- `cluster_name → 활성 streams` map 입니다. 같은 cluster 의 여러 agent stream 을 보존하며, leader 는 가장 오래된 stream 입니다.
- `request_id → CompletableFuture` Caffeine bounded cache (size 10,000 / TTL 5분) 입니다. stuck agent 시
  OOM 방지 + caller 의 `orTimeout` 과 함께 race-safe 합니다.
- 호출 entry 는 `KubeServiceImpl.requireAgent`, `HelmReleaseService`, `KubeResourceService`,
  lifecycle starter 의 backup 서비스 (`EtcdBackupServiceImpl`, `PkiBackupServiceImpl` 등) 입니다.

Backend single-instance 를 가정합니다. Multi-replica 환경은 gateway sticky-session by `cluster_id` 를 권장합니다.
Cross-instance forward / ownership 분산은 backend instance 가 10+ 또는 sticky 불가 환경 도달 시 별도 sprint 항목 — 현재는 미진행.

## Repository layout

```
any-cloud-management/
├── anycloud/          # Spring backend (Java) — REST + gRPC server + RabbitMQ workflow
├── libs/             # Spring Boot starter (agent, features, provisioning)
├── agent/             # Cluster Agent (Go) — in-cluster Pod
│   ├── cmd/cluster-agent/
│   ├── internal/
│   │   ├── core/        # event watcher, stream client (reverse gRPC)
│   │   ├── controller/  # helm SDK, kubectl exec, gRPC handlers
│   │   ├── auth/        # registration_token / agent_identity_token
│   │   └── config/      # allowlist (charts/namespaces/commands)
│   └── deploy/helm/   # Helm chart (Phase 2+)
├── proto/             # Shared proto (Java backend + Go agent)
│   └── agent/v1/
│       ├── common.proto
│       ├── bootstrap.proto
│       ├── runtime.proto
│       └── events.proto
└── docker-compose.dev.yml   # MariaDB + RabbitMQ
```

## Auth model — 2-tier tokens

| Token | Lifetime | Issuer | Purpose | Channel |
|---|---|---|---|---|
| `registration_token` | 5–10 min (JWT) | Backend | Bootstrap (1회용) | gRPC bootstrap channel, unauthenticated TLS |
| `agent_identity_token` | 60 days (UUID opaque or mTLS cert) | Backend | Runtime stream 인증 | gRPC runtime stream, Authorization: Bearer |

`registration_token` 은 JWT 이므로 backend 가 DB 조회 없이 `iss/aud/sub/scope/exp` 를 검증할 수 있습니다. JTI 는
MariaDB `bootstrap_jti_used` 테이블에 `INSERT IGNORE` 로 1회 사용을 강제합니다. 만료된 jti 는 일배치 sweeper 가 정리합니다.

`agent_identity_token` 은 DB lookup 이 필수입니다 (revoke 가능합니다). mTLS cert 는 Root CA (offline) →
Intermediate CA (online, agent 전용) → per-cluster Agent cert 의 chain 입니다. TTL 50% 도달 시 자동 rotation 됩니다.

## Install modes

| 모드 | 설치 주체 | 인증 수단 | 실패 시 |
|---|---|---|---|
| **MANUAL** | 사용자 `kubectl apply agent.yaml` | `registration_token` env | Agent self-terminate |
| **HELM_BOOTSTRAP** | 사용자 `helm install` | `registration_token` value | Helm uninstall (cleanup) |
| **API_MANAGED** | Backend 가 kubeconfig 로 원격 설치 | TTL kubeconfig + bearer token | DEGRADED 보고 |

Pulumi-provisioned cluster 는 HELM_BOOTSTRAP 의 자동화 variant 입니다 — Pulumi user-data 마지막에 helm install 이 자동 실행됩니다.

## State machine

```
[INIT]              → Agent container 시작
    ↓
[BOOTSTRAPPED]      → values.yaml / Secret 로딩, api_endpoint 결정
    ↓
[REGISTERING]       → AgentBootstrap.Register RPC 호출 + registration_token 사용
    ↓
[REGISTERED]        → Backend DB transaction 후 agent_identity_token 수신 + Secret 저장
    ↓
[ACTIVE]            → Runtime gRPC stream, command/event/metrics 처리

[FAILED]            → 등록 실패 (token 만료, cluster_id 충돌). Rollback 정책 install_mode 별로 상이.
[DEGRADED]          → API_MANAGED 실패 등 비정상/제한 동작.
```

gRPC handler 가 JWT 검증 + DB upsert + identity_token 발급까지 한 transaction 으로 처리합니다. 같은
(cluster_id, agent_instance_id) 조합은 upsert 로 멱등성을 보장합니다.

## Event schemas (`events.proto`)

| Routing key prefix | Producer | Consumer | Payload |
|---|---|---|---|
| `cluster.registration.*` | Agent | Backend | `ClusterInstallCompleted`, `ClusterRegistered`, `ClusterAgentFailed` |
| `cluster.lifecycle.*` | Backend | UI / 외부 | `ClusterStatusChanged` |
| `cluster.events.*` | Agent | Backend | `ClusterInternalEvent` (pod.crashed / addon.installed 등) |

전송 layer 는 RabbitMQ topic exchange 입니다. Routing key prefix 에 `cluster_id` 를 포함하면 같은
cluster 이벤트가 같은 queue 로 가도록 binding 할 수 있습니다 (in-order 처리). 현재 publisher 는 부재하며 —
schemas 만 starter 에 유지합니다.

## RBAC (Multi-Pod 패턴)

Agent 는 두 Pod 으로 분리할 수 있습니다.

- **aipaas-agent-core** (read-only): pods/nodes/namespaces/events get/list/watch + apps/deployments 권한입니다. Helm/installer 권한은 없습니다.
- **aipaas-agent-installer** (sidecar, on-demand): Helm/manifest 적용 권한입니다. 설치 완료 후 종료할 수 있습니다.

AllowList ConfigMap 으로 chart name + version range + namespace + command type 화이트리스트를 강제합니다.

## 참고

- 코드: `domain/kube/`, `domain/provisioning/bootstrap/`, `domain/provisioning/remote/` 입니다.
- Industry 유사 패턴: ArgoCD application-controller, Rancher Fleet, OpenShift ACM klusterlet 이 있습니다.
