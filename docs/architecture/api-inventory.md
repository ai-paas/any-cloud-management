# API Inventory

anycloud 가 노출하는 REST + gRPC 인터페이스 전수 목록입니다. 도메인별로 grouping 합니다. 인증 model 은 §1 을 참고합니다.
flow 는 [`feature-flows.md`](./feature-flows.md) 를 참고합니다.

> **갱신 정책**: 본 doc 은 수기 유지 — OpenAPI publish 자동 workflow 와 별도. endpoint 추가/변경
> PR 에서 함께 갱신 필수. 자동 검증은 없으므로 reviewer 가 본 doc 의 해당 도메인 섹션 점검 권장.

## 1. 인증 모델

| Mode | 동작 |
|---|---|
| **Default (production)** | backend 는 인증 X. gateway 가 외부 IDP 위임 — `X-User-*` header 가 신뢰됨. |
| **Optional static token** | `security.auth.enabled=true` 시 Bearer token 검증 (`security.auth.token`). 로컬/스테이지용. |
| **Break-glass (운영 fallback)** | Keycloak outage 시 gateway 우회. 위 static token 모드와 동일 mechanism. runbook: [`docs/runbooks/keycloak-outage.md`](../runbooks/identity/keycloak-outage.md). 운영팀 vault 보관 + 월 1회 rotation 필수. |
| **Public path** | 인증 무관 — `/actuator/health`, `/docs/**`, `/swagger-ui/**`, `/v3/api-docs/**`, `/v1/agent-bootstrap/**` |
| **Anonymous (의도)** | `/v1/agent-bootstrap/ca-bundle*` — agent helm install 전 CA 받는 용도. MITM 방어는 OOB fingerprint 비교 (startup banner). |

> **Gateway 의 JWKS cache 권장 설정**: TTL ≥ 12시간. Keycloak 잠시 down (재시작/upgrade) 해도
> 기존 JWT 검증 정상 동작. 자세한 fallback 전략: [`docs/runbooks/keycloak-outage.md`](../runbooks/identity/keycloak-outage.md).

gRPC 인증:
- `AgentBootstrap.Register` — Bearer `registration_token` (10분 TTL JWT).
- `AgentRuntime.Stream` / `PodExec` / `StreamPodLogs` — Bearer `identity_token` (60일 opaque) **또는** client cert.
- `AgentBootstrap.RenewCert` — mTLS (current cert) + Bearer `identity_token`.

## 2. REST endpoint — 도메인별

### 2.1 Cluster Management (`/v1/clusters`)

K8s cluster 자원 — registered (수동) + agent self-registered (VM provisioning 후 자동) 모두.

| Method | Path | 설명 | Controller |
|---|---|---|---|
| GET | `/v1/clusters` | cluster 목록 (source/provider/env/status filter) | `ClusterController` |
| GET | `/v1/clusters/{name}` | 단일 cluster (source 자동 판별) | `ClusterController` |
| POST | `/v1/clusters` | **외부 등록 전용** (source=vm 거부 — `POST /v1/vms` 사용) | `ClusterController` |
| PATCH | `/v1/clusters/{name}` | cluster 수정 (scale / upgrade) | `ClusterController` |
| PATCH | `/v1/clusters/{name}/capabilities` | capability flag 수동 셋 | `ClusterController` |
| DELETE | `/v1/clusters/{name}` | cluster 삭제 (`cluster_agent` cascade) | `ClusterController` |
| POST | `/v1/clusters/{name}/operations` | action op 제출 (retry/refresh) | `ClusterController` |
| GET | `/v1/clusters/{name}/operations` | op 이력 (최신순) | `ClusterController` |
| GET | `/v1/clusters/{name}/state-history` | 상태 전이 audit | `ClusterController` |
| POST | `/v1/clusters/{name}/connectivity-checks` | K8s API connectivity 테스트 | `ClusterController` |

### 2.1.1 VM 인프라 자원 (`/v1/vms`)

Pulumi 로 만들어진 CSP VM 인프라. K8s cluster registration 과 lifecycle 분리.

| Method | Path | 설명 | Controller |
|---|---|---|---|
| GET | `/v1/vms` | VM 목록 (provider/environment/status filter) | `VmController` |
| GET | `/v1/vms/{name}` | 단일 VM (workflow / stack outputs) | `VmController` |
| POST | `/v1/vms` | VM 그룹 생성 — body 의 `vmGroupName` 으로 master + worker 인스턴스 집합 한 번에 프로비저닝 (Pulumi — 202 + Operation) | `VmController` |
| PATCH | `/v1/vms/{name}` | VM scale (workerCount 변경) | `VmController` |
| DELETE | `/v1/vms/{name}` | VM 삭제 (Pulumi destroy — 202) | `VmController` |
| POST | `/v1/vms/{name}/operations` | retryWorkflow / retryRegistration / refreshStatus | `VmController` |
| GET | `/v1/vms/{name}/operations` | VM operation 이력 | `VmController` |
| GET | `/v1/vms/{name}/state-history` | workflow state transition 이력 | `VmController` |
| GET | `/v1/vms/{name}/nodes` | VM 노드 목록 (role/publicIp/privateIp) | `VmController` |
| POST | `/v1/vms/{name}/ssh-key?format=json\|pem` | VM SSH private key 발급 | `VmController` |
| GET | `/v1/vms/{name}/kubeconfig` | kubeconfig YAML (단기 SA token) | `VmController` |

`vm_cluster.cluster_id` (FK → `cluster.id`, `ON DELETE SET NULL`) 로 1:1 link.
agent register 또는 VERIFY step 에서 backfill.

### 2.2 Cluster Health (`/v1/clusters/.../health`)

| Method | Path | 설명 | Controller |
|---|---|---|---|
| GET | `/v1/clusters/{name}/health` | cluster health 요약 (API/agents/workloads) | `ClusterHealthController` |
| GET | `/v1/agents/health` | fleet-wide agent health | `ClusterHealthController` |

### 2.3 Cluster Access & Debugging

| Method | Path | 설명 | Controller |
|---|---|---|---|
| POST | `/v1/clusters/{name}/nodes/{nodeName}/debug-pod` | 임시 privileged debug pod | `ClusterAccessController` |
| GET | `/v1/clusters/{name}/kubeconfig?serviceAccount=&namespace=&ttlSeconds=` | kubeconfig 다운로드 (YAML attachment, agent SA token). SA 미지정 시 VM(PULUMI) cluster 는 자동 admin SA, registered 는 명시 필요 (III-61 단일 엔드포인트) | `ClusterAccessController` |

⚠️ tech-debt: `KubernetesClientFactory.createKubeconfigContent` 가 `insecure-skip-tls-verify: true` 하드코딩.
serverCa 보유 시에도 `certificate-authority-data` 미발급.

### 2.4 K8s Resource Proxy (`/v1/clusters/{name}/namespaces/...`)

| Method | Path | 설명 |
|---|---|---|
| GET | `/v1/clusters/{n}/namespaces/{ns}/{kind}` | 리소스 목록 (server-side pagination) |
| GET | `/v1/clusters/{n}/namespaces/{ns}/{kind}/{resourceName}` | 단일 리소스 |
| POST | `/v1/clusters/{n}/namespaces/{ns}/{kind}` | server-side apply |
| DELETE | `/v1/clusters/{n}/namespaces/{ns}/{kind}/{resourceName}` | 삭제 |
| GET | `/v1/clusters/{n}/namespaces/{ns}/pods/{podName}/logs` | log snapshot (text) |
| GET | `/v1/clusters/{n}/namespaces/{ns}/pods/{podName}/logs/stream` | SSE log tail -f |

모두 `ClusterKubernetesController`.

### 2.5 Resource Catalog & Discovery

| Method | Path | 설명 |
|---|---|---|
| GET | `/v1/clusters/{n}/resource-kinds` | cluster 지원 kinds 목록 |
| GET | `/v1/clusters/{n}/resolve` | kind name 단일 해상 |

Controller: `ClusterResourceCatalogController`.

### 2.6 Agent Bootstrap & Registration (`/v1/agent-bootstrap`, `/v1/cluster-agent`, `/v1/clusters/.../agent-*`)

| Method | Path | 설명 | Auth |
|---|---|---|---|
| GET | `/v1/agent-bootstrap/ca-bundle.pem` | backend CA (raw PEM, text/plain) | **None** |
| GET | `/v1/agent-bootstrap/ca-bundle` | backend CA (JSON envelope) | **None** |
| POST | `/v1/clusters/{id}/agent-registration` | bootstrap JWT 발급 (10분 TTL, jti 1회용) | Required |
| GET | `/v1/clusters/{id}/agent/pods` | [TEST] agent 통한 pod 조회 | Required |
| GET | `/v1/clusters/{id}/agent/logs` | [TEST] agent 통한 pod log | Required |
| GET | `/v1/admin/agent/heartbeat-staleness` | heartbeat staleness 임계값 조회 | Required |
| POST | `/v1/admin/agent/heartbeat-staleness` | 임계값 수정 (in-memory) | Required |

VM 프로비저닝 후의 cluster 등록은 BOOTSTRAP step 안에서 자동 (VmClusterRegistrationServiceImpl.createClusterEntity).
수동 cluster 등록은 `POST /v1/clusters` 호출 후 응답의 helm/kubectl 명령 실행 — agent 가 gRPC dial-in 으로 ACTIVE.

Controllers: `AgentBootstrapPublicController`, `AgentRegistrationController`, `AgentCommandTestController`, `AdminAgentController`.

⚠️ tech-debt: `/v1/agent-bootstrap/*` rate limit 없음. anonymous + 결정적 응답.

### 2.7 Agent Cert / Identity Revocation (`/v1/admin/clusters/.../cert`)

| Method | Path | 설명 |
|---|---|---|
| GET | `/v1/admin/clusters/{name}/cert` | per-cluster cert metadata |
| POST | `/v1/admin/clusters/{name}/cert/revoke` | cluster-wide / cert-serial revoke |
| POST | `/v1/admin/clusters/{name}/cert/unrevoke` | revoke 복구 |

Controller: `AdminAgentCertController`.

### 2.8 Agent Policy (`/v1/admin/clusters/.../agent-policy` + `/v1/admin/agent/policy/*`)

| Method | Path | 설명 |
|---|---|---|
| GET | `/v1/admin/agent/policy/preview` | policy snapshot + validation warnings |
| GET | `/v1/admin/agent/policy/audit` | fleet-wide audit (parallel fetch with timeout) |
| PUT | `/v1/admin/clusters/{name}/agent-policy` | 전체 replace (3개 list 필수) |
| PATCH | `/v1/admin/clusters/{name}/agent-policy` | legacy partial update (null=keep) |
| PATCH | `/v1/admin/clusters/{name}/agent-policy` | RFC 7396 merge patch (null=clear) |

Controller: `AdminAgentPolicyController` (771 LOC — split 권고).

### 2.9 Backend CA (`/v1/admin/backend-ca`)

| Method | Path | 설명 |
|---|---|---|
| POST | `/v1/admin/backend-ca/rotate` | CA 회전. default ttlYears=10, 1..50 |

Controller: `AdminBackendCaController`.

### 2.10 State Machine Admin (`/v1/admin/state-machine`)

| Method | Path | 설명 |
|---|---|---|
| GET | `/v1/admin/state-machine/vmcluster` | runtime state machine graph |
| GET | `/v1/admin/state-machine/vmcluster/strict` | strict 검증 toggle 조회 |
| POST | `/v1/admin/state-machine/vmcluster/strict` | strict 검증 toggle 수정 |

Controller: `AdminStateMachineController`.

### 2.11 Fleet Upgrade (`/v1/fleet/upgrade`, `/v1/clusters/.../upgrade*`)

| Method | Path | 설명 |
|---|---|---|
| GET | `/v1/fleet/upgrade/preview` | wave 별 cluster 분포 + 버전 카운트 |
| PATCH | `/v1/clusters/{name}/upgrade-wave` | wave 재할당 (같은 cluster 의 모든 agent row 동기) |
| POST | `/v1/clusters/{name}/upgrade` | 단일 cluster agent 업그레이드 |
| GET | `/v1/fleet/upgrade/runs` | fleet upgrade 이력 (top 20) |

Controller: `FleetUpgradeController`.

### 2.12 Operations & Async Task Tracking (`/v1/operations`)

| Method | Path | 설명 |
|---|---|---|
| GET | `/v1/operations` | op 검색 (filter) |
| GET | `/v1/operations/{id}` | 단일 op 상태 |
| POST | `/v1/operations/{id}/cancel` | best-effort cancel |
| GET | `/v1/operations/{id}/events` | op progress SSE |
| GET | `/v1/clusters/{name}/events` | cluster 이벤트 SSE |

Controllers: `OperationController`, `OperationEventsController`.

### 2.13 Cluster Validation (`/v1/cluster-validations`)

| Method | Path | 설명 |
|---|---|---|
| POST | `/v1/cluster-validations` | VM cluster 생성 pre-flight |

Controller: `ClusterValidationController`.

### 2.14 CSP Credentials (`/v1/credentials`)

| Method | Path | 설명 |
|---|---|---|
| GET | `/v1/credentials` | 목록 |
| GET | `/v1/credentials/{id}` | 메타 |
| POST | `/v1/credentials` | 생성 (manual / env-var 기반) |
| DELETE | `/v1/credentials/{id}` | 삭제 |

Controller: `CspCredentialController`. 암호화는 `CspCredentialCryptoService` 위임.

### 2.15 VM / Cloud Provider Options (`/v1/providers`)

| Method | Path | 설명 |
|---|---|---|
| GET | `/v1/providers` | 지원 CSP + impl status |
| GET | `/v1/providers/{p}/regions` | region 목록 |
| GET | `/v1/providers/{p}/specs` | instance type / flavor |
| GET | `/v1/providers/{p}/config-schema` | provisioning config JSON schema |
| GET | `/v1/providers/{p}/images` | OS image 목록 |

Controller: `VmOptionsController`. 각 CSP impl 은 `service.vmoptions.providers/`.

### 2.16 Helm Releases (`/v1/clusters/.../helm-releases`)

| Method | Path | 설명 |
|---|---|---|
| GET | `/v1/clusters/{n}/helm-releases` | release 목록 |
| POST | `/v1/clusters/{n}/helm-releases` (JSON) | values 객체 / YAML 문자열로 install |
| POST | `/v1/clusters/{n}/helm-releases` (multipart) | values.yaml 업로드로 install |
| GET | `/v1/clusters/{n}/helm-releases/{name}` | release 상태 |
| GET | `/v1/clusters/{n}/helm-releases/{name}/revisions` | revision 타임라인 |
| POST | `/v1/clusters/{n}/helm-releases/{name}/operations` | rollback 등 action |
| GET | `/v1/clusters/{n}/helm-releases/{name}/resources` | release 가 만든 K8s resource |
| DELETE | `/v1/clusters/{n}/helm-releases/{name}` | uninstall (LRO) |

Controller: `ClusterHelmReleaseController`.

### 2.17 Helm Repos (`/v1/helm-repos`)

| Method | Path | 설명 |
|---|---|---|
| GET | `/v1/helm-repos` | repo 목록 |
| GET | `/v1/helm-repos/{name}` | repo 메타 |
| POST | `/v1/helm-repos` | repo 추가 |
| PATCH | `/v1/helm-repos/{name}` | repo 수정 |
| DELETE | `/v1/helm-repos/{name}` | repo 삭제 |
| GET | `/v1/helm-repos/{name}/charts` | repo 내 chart 목록 |
| GET | `/v1/helm-repos/{name}/charts/{chart}` | chart 상세 |
| GET | `/v1/helm-repos/{name}/charts/{chart}/values` | default values.yaml |
| GET | `/v1/helm-repos/{name}/charts/{chart}/readme` | README.md |

Controllers: `HelmRepoController`, `HelmRepoChartController`.

### 2.18 Observability & Metrics

| Method | Path | 설명 |
|---|---|---|
| GET | `/v1/clusters/{n}/node-metrics` | node 자원 metric |
| GET | `/v1/clusters/{n}/resource-metrics/{type}/{key}` | 리소스별 metric |
| POST | `/v1/clusters/{n}/observability/install` | kube-prometheus-stack 자동 설치 |
| GET | `/v1/clusters/{n}/metrics/query` | PromQL instant query |
| GET | `/v1/clusters/{n}/metrics/query_range` | PromQL range query |
| GET | `/v1/observability/aggregate` | fleet-wide PromQL fan-out |
| GET | `/v1/clusters/{n}/observability/targets` | Prometheus scrape target 상태 |
| GET | `/v1/clusters/{n}/observability/alerts` | Alertmanager active alerts |
| GET | `/v1/clusters/{n}/observability/dashboard` | Grafana URL |
| GET | `/v1/observability/standard-queries` | 표준 PromQL 카탈로그 |
| GET | `/v1/clusters/{n}/metrics/standard/{...}` | node-cpu / node-memory / namespace-cpu / namespace-memory / pod-phases / top-cpu |

Controllers: `MonitoringController`, `ObservabilityController`.

### 2.19 Audit (`/v1/audit-logs`)

| Method | Path | 설명 |
|---|---|---|
| GET | `/v1/audit-logs` | audit 검색 (filter) |

Controller: `AuditLogController`.

### 2.20 VM Workflow (`/v1/workflow/*`)

| Method | Path | 설명 |
|---|---|---|
| GET | `/v1/workflow/queues` | RabbitMQ workflow queue 상태 |
| GET | `/v1/workflow/dead-letter-messages` | DLQ 메시지 목록 |
| POST | `/v1/workflow/dead-letter-messages/{id}/operations` | DLQ 메시지 action |

Controller: `VmClusterWorkflowController`.

## 3. gRPC services

### 3.1 `AgentBootstrap` — `proto/agent/v1/bootstrap.proto`

| RPC | Request | Response | Auth | 설명 |
|---|---|---|---|---|
| `Register` | `RegisterRequest` (ClusterIdentity + AgentIdentity + CSR) | `RegisterResponse` (cluster_id, agent_identity_token, expires_at, optional IssuedCert) | Bearer `registration_token` | 첫 등록 + cert 발급 |
| `RotateIdentityToken` | `RotateRequest` (current token hash) | `RotateResponse` (new token + expires_at) | Bearer `identity_token` | identity token 회전 (60일 → 새 60일) |
| `RenewCert` | `RenewCertRequest` (CSR, currentCertSerial) | `RenewCertResponse` (new cert) | Bearer `identity_token` + mTLS (Phase 4+) | TTL 50% 도달 시 |

### 3.2 `AgentRuntime` — `proto/agent/v1/runtime.proto`

| RPC | Request stream | Response stream | 설명 |
|---|---|---|---|
| `Stream` | `AgentMessage` (heartbeat / command response / event) | `ControlMessage` (CommandRequest / Heartbeat / Shutdown / OpenExecSession / OpenLogStream) | bidi 양방향. heartbeat 30s |
| `PodExec` | `ExecPacket` (첫 패킷=ExecRequest, 이후=stdin chunk) | `ExecPacket` (stdout/stderr/exit) | interactive exec |
| `StreamPodLogs` | `LogPacket` (첫 패킷=LogStreamRequest) | `LogPacket` (LogChunk) | tail -f |

**Command type enum** (`CommandRequest.type`):
- `LIST_PODS`, `GET_LOG`, `SCALE_DEPLOYMENT`
- `INSTALL_ADDON`, `UNINSTALL_ADDON` (allowlist 검증)
- `APPLY_MANIFEST` (fleet upgrade 등)
- `HEARTBEAT`

### 3.3 `events.proto` — pub/sub schema

`AgentRuntime` 와 별개로 routing key 기반 이벤트 메시지입니다.

| Routing key | 이벤트 |
|---|---|
| `cluster.registration.*` | agent 등록 단계 |
| `cluster.lifecycle.*` | cluster 상태 전이 |
| `cluster.events.*` | 내부 이벤트 (pod 충돌, addon install) |

`ClusterInstallCompleted`, `ClusterRegistered`, `ClusterAgentFailed`, `ClusterStatusChanged` 등이 있습니다. 현재
synchronous register 경로에선 미사용이며 — 향후 async saga / audit pipeline 용도로 사용됩니다.

## 4. 인증 / 권한 요약표

| Endpoint 그룹 | Default | Anonymous? | 비고 |
|---|---|---|---|
| `/actuator/health`, `/docs/**`, `/v3/api-docs/**` | gateway pass-through | yes | infra healthcheck |
| `/v1/agent-bootstrap/ca-bundle*` | gateway pass-through | **yes (의도)** | OOB fingerprint 검증 권장 |
| `/v1/clusters/*` | gateway 인증 | no | 일반 사용자 |
| `/v1/admin/**` | gateway 인증 + admin role | no | 운영자 전용 |
| gRPC `AgentBootstrap.Register` | Bearer JWT | — | 10분 TTL, jti 1회 |
| gRPC `AgentRuntime.*` | Bearer identity_token (+ mTLS 후속) | — | 60일 |

## 5. Response 공통 envelope

성공:
```json
{
  "success": true,
  "status": 200,
  "message": "...",
  "data": { ... },
  "meta": { "requestId": "...", "timestamp": "...", "processingTimeMs": 123 },
  "links": null
}
```

실패 (`GlobalExceptionHandler`):
```json
{
  "success": false,
  "status": 400,
  "code": "INVALID_INPUT_VALUE",
  "message": "..."
}
```

`ResponseEnvelopeAdvice` 가 `meta` 를 응답 직전 자동으로 채웁니다.

## 6. 통계

- REST endpoint: ~120 (25 도메인)
- gRPC services: 2 active + 1 schema (events)
- Controllers: 25
- 인증 기본값: gateway-managed (Spring Security 자체는 toggle)
- Public endpoint: 3 그룹 (health, docs, ca-bundle)
- SSE endpoint: 4 (operation events, cluster events, log stream, op progress)

## 7. 관련 문서

- [overview.md](./overview.md) — component 다이어그램
- [feature-flows.md](./feature-flows.md) — 실제 호출 sequence
- OpenAPI: `/v3/api-docs` 또는 `/swagger-ui.html` (runtime)
- gRPC proto: `libs/cluster-agent-spring-boot-starter/src/main/proto/agent/v1/` 입니다.
