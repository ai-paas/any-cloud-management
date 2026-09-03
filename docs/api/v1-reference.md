# anycloud `/v1` API Reference

RESTful 자원 모델 기반 API 입니다. 모든 endpoint 가 `/v1` prefix 를 사용하며, legacy alias 는 제공되지 않습니다.

## 핵심 원칙

| 항목 | 규칙 |
|---|---|
| Versioning | 모든 endpoint 가 `/v1/` prefix |
| 자원명 | 명사 복수 + kebab-case (`/clusters`, `/helm-repos`, `/audit-logs`) |
| 동사 | HTTP method 만 사용. URL 에 동사 없음 |
| State 변경 | `PATCH /resource/{id}` body=`{spec:{...}}` |
| Long-running action | `POST /resource/{id}/operations` body=`{type, ...}` → Operation 자원 |
| 검증 / 검사 결과 | 별도 자원 (`/cluster-validations`, `/connectivity-checks`) |
| Path variable | camelCase (`{clusterName}`, `{credentialId}`) |
| Query param | camelCase (`pageSize`, `pageToken`, `labelSelector`) |
| Pagination | `pageSize` + `pageToken` (opaque cursor) |
| 응답 wrapper | `{success, status, message, data, meta?, links?}` |
| `meta` | `requestId / timestamp / processingTimeMs / pagination?` |
| Idempotency | 모든 POST/PATCH 에 `Idempotency-Key` 헤더 지원 (24h) |
| 실시간 | `GET .../events` SSE |
| Long-running 시작 | 202 Accepted + `Location: /v1/operations/{id}` |
| HMAC webhook | `X-Anycloud-Signature: sha256=<hex>` |

---

## 자원 트리

```
/v1
├── providers/
│   └── {provider}/{regions|specs|images}
│
├── credentials/{credentialId}
│
├── clusters/
│   ├── {clusterName}/
│   │   ├── operations/                              # cluster scope LRO
│   │   ├── connectivity-checks                      # POST → 검사 결과 resource
│   │   ├── events                                   # SSE
│   │   ├── node-metrics
│   │   ├── resource-metrics/{type}/{key}
│   │   ├── namespaces/{ns}/{kind}                   # K8s resources
│   │   │   └── {name}
│   │   └── helm-releases/{release}
│   │       ├── revisions
│   │       ├── resources
│   │       └── operations/                          # release scope LRO
│
├── cluster-validations                              # preflight resource
│
├── helm-repos/{repoName}/
│   └── charts/{chartName}/{values|readme}
│
├── operations/
│   ├── {operationId}
│   └── {operationId}/cancel                         # custom method 서브패스
│   └── {operationId}/events                         # SSE
│
├── workflow/                                        # admin
│   ├── queues
│   └── dead-letter-messages/{messageId}/operations  # type=replay
│
└── audit-logs
```

---

## 전체 endpoint 표

### Clusters

| Method | Path | 설명 |
|---|---|---|
| GET    | `/v1/clusters?source=&provider=&environment=&status=` | 통합 list (vm + registered) |
| GET    | `/v1/clusters/{name}` | source 자동 감지 |
| POST   | `/v1/clusters` | body source=vm|registered, 202(VM) / 201(reg) + Location |
| PATCH  | `/v1/clusters/{name}` | body=`{spec:{workerCount?, kubernetesVersion?}}` → operation |
| DELETE | `/v1/clusters/{name}` | 202 + Location |
| POST   | `/v1/clusters/{name}/operations` | body=`{type:retryWorkflow|retryRegistration|refreshStatus}` → 202 + Operation (state=SUCCEEDED 면 즉시 완료) |
| GET    | `/v1/clusters/{name}/operations?pageSize=` | 이 cluster 의 operation 이력 |
| POST   | `/v1/clusters/{name}/connectivity-checks` | K8s API 연결 검사 결과 자원 |
| GET    | `/v1/clusters/{name}/events` | SSE — cluster lifecycle |
| GET    | `/v1/clusters/{name}/kubeconfig?serviceAccount=&namespace=&ttlSeconds=` | kubeconfig YAML 다운로드 (`application/yaml`, attachment). agent 가 SA 의 단기 token 발급 후 합성 (H-48: 정적 admin 자격 미보관). serviceAccount 미지정 시 VM(PULUMI) cluster 의 자동 admin SA(기본 `aipaas-admin`/`aipaas-system`)로 전체 권한 다운로드(III-60); registered/BYO 는 존재하는 SA 명시 필요. SA 미존재 404 / agent 미연결 503 |

### Cluster Validations (preflight)

| Method | Path | 설명 |
|---|---|---|
| POST | `/v1/cluster-validations` | preflight 결과 자원 (201 + 결과 + createCluster 링크) |

### Kubeconfig 파일 업로드 등록 (multipart)

| Method | Path | 설명 |
|---|---|---|

### K8s Resources (단일 controller — cluster-scoped 포함)

| Method | Path | 설명 |
|---|---|---|
| GET    | `/v1/clusters/{c}/namespaces/{ns}/{kind}?pageSize=&pageToken=&labelSelector=` | kind list (server-side pagination) |
| GET    | `/v1/clusters/{c}/namespaces/{ns}/{kind}/{name}` | 단건 |
| POST   | `/v1/clusters/{c}/namespaces/{ns}/{kind}` | manifest apply (kubectl apply 등가). body 는 YAML 또는 JSON, 멀티 doc (`---`) 지원. server-side apply. metadata.namespace 없으면 path ns 적용. |
| DELETE | `/v1/clusters/{c}/namespaces/{ns}/{kind}/{name}` | 삭제 |

**`{ns}` path 컨벤션**

- 일반 namespace 이름 — 해당 namespace 한정
- `_all` — 모든 namespace (all-namespaces sentinel)
- `-` — cluster-scoped kind 일 때 K8s 컨벤션 (값은 무시됨)

**Cluster-scoped kinds** (어떤 `{ns}` 가 와도 서버에서 무시):
`nodes`, `namespaces`, `persistentvolumes`, `storageclasses`, `customresourcedefinitions`

예시:
- `GET /v1/clusters/demo/namespaces/-/nodes` — 모든 노드
- `GET /v1/clusters/demo/namespaces/-/customresourcedefinitions/<name>` — CRD 단건
- `GET /v1/clusters/demo/namespaces/_all/pods` — 모든 namespace 의 pod
- `GET /v1/clusters/demo/namespaces/web/deployments` — `web` namespace 의 deployment

### Helm Releases (cluster sub-resource)

| Method | Path | 설명 |
|---|---|---|
| GET    | `/v1/clusters/{c}/helm-releases?namespace=` | release list |
| POST   | `/v1/clusters/{c}/helm-releases` | install (chart + valuesYaml) → 202 + Operation |
| GET    | `/v1/clusters/{c}/helm-releases/{r}?namespace=` | status |
| GET    | `/v1/clusters/{c}/helm-releases/{r}/revisions?max=10` | history |
| POST   | `/v1/clusters/{c}/helm-releases/{r}/operations` | type=rollback + revision/wait |
| GET    | `/v1/clusters/{c}/helm-releases/{r}/resources` | release 의 K8s 리소스 |
| DELETE | `/v1/clusters/{c}/helm-releases/{r}?namespace=&keepHistory=&wait=` | uninstall (LRO) → 202 + Operation. `keepHistory=true` 면 revision 이력 보존, `wait=true` 면 자원 삭제 완료까지 대기 |

### Helm Repos & Charts

| Method | Path | 설명 |
|---|---|---|
| GET    | `/v1/helm-repos` | repo list |
| POST   | `/v1/helm-repos` | 신규 repo |
| GET    | `/v1/helm-repos/{repoName}` | repo 상세 |
| DELETE | `/v1/helm-repos/{repoName}` | 삭제 |
| GET    | `/v1/helm-repos/{repoName}/charts` | chart catalog |
| GET    | `/v1/helm-repos/{repoName}/charts/{chartName}?version=` | chart 상세 |
| GET    | `/v1/helm-repos/{repoName}/charts/{chartName}/values?version=` | values.yaml |
| GET    | `/v1/helm-repos/{repoName}/charts/{chartName}/readme?version=` | README.md |

### Providers (카탈로그, read-only)

| Method | Path | 설명 |
|---|---|---|
| GET | `/v1/providers` | provider list |
| GET | `/v1/providers/{provider}/regions` |
| GET | `/v1/providers/{provider}/specs?region=&keyword=&gpuOnly=&limit=` |
| GET | `/v1/providers/{provider}/images?region=&keyword=&architecture=&owner=&limit=` |

### CSP Credentials

| Method | Path | 설명 |
|---|---|---|
| GET    | `/v1/credentials` | list |
| POST   | `/v1/credentials` | 생성 |
| GET    | `/v1/credentials/{credentialId}` | 단건 |
| DELETE | `/v1/credentials/{credentialId}` | 삭제 |

### Operations (Long-Running)

| Method | Path | 설명 |
|---|---|---|
| GET    | `/v1/operations?state=&type=&resourceType=&resourceId=&pageSize=` | 전역 검색 |
| GET    | `/v1/operations/{operationId}` | 단건 |
| POST   | `/v1/operations/{operationId}/cancel` | 취소 요청 (best-effort) |
| GET    | `/v1/operations/{operationId}/events` | SSE — state/progress push |

### Monitoring (cluster sub-resource)

| Method | Path | 설명 |
|---|---|---|
| GET | `/v1/clusters/{c}/node-metrics` | 노드별 상태/사용량 |
| GET | `/v1/clusters/{c}/resource-metrics/{type}/{key}?...filters` | 리소스 metric (Prometheus 프록시) |

### Workflow (admin)

| Method | Path | 설명 |
|---|---|---|
| GET  | `/v1/workflow/queues` | RabbitMQ queue 상태 |
| GET  | `/v1/workflow/dead-letter-messages?clusterName=&limit=` | FAILED 메시지 |
| POST | `/v1/workflow/dead-letter-messages/{messageId}/operations` | body=`{type:"replay"}` |

### Audit Logs

| Method | Path | 설명 |
|---|---|---|
| GET | `/v1/audit-logs?since=&until=&resourceType=&resourceId=&action=&principal=&limit=` | 시간 윈도우 + 필터 |

#### Audit 자동 캡처 규칙

`AuditInterceptor` 가 모든 mutation HTTP 요청 (POST/PUT/PATCH/DELETE) 을 자동 기록합니다.
GET 은 audit 대상이 아닙니다.

**resourceType 매핑** (URI prefix 기반):

| URI prefix | resourceType |
|---|---|
| `/v1/clusters/{c}/helm-releases…` | `helmRelease` |
| `/v1/clusters/{c}/namespaces/…` | `k8sResource` |
| `/v1/clusters…` (위 두 케이스 외, `:importKubeconfig` 포함) | `cluster` |
| `/v1/cluster-validations` | `clusterValidation` |
| `/v1/operations…` | `operation` |
| `/v1/helm-repos…` | `helmRepo` |
| `/v1/audit-logs` | `auditLog` |

**resourceId 추출**:

- 단순: 마지막 path segment (`/v1/clusters/demo-aws-01` → `demo-aws-01`)
- action-suffix verb (`operations`, `connectivity-checks`, `revisions`, `resources`, `events`,
  `helm-releases`, `rollback`, `retry`, `cancel`, `flush`, `install-all`, `enqueue`,
  `importKubeconfig` 등) 는 한 단계 위 segment 가 ID
  (`/v1/clusters/demo/operations` → `demo`, `/v1/operations/op-1/cancel` → `op-1`)
- collection-level custom method 은 ID 없음 (`/v1/clusters/importKubeconfig` → null; controller 가
  form param 으로 보강 가능)
- (III-58: 모든 콜론 `:verb` custom method 를 colon-free 서브패스로 전환 — Boot3 라우팅/proxy 안전)

**action 이름**:

`<controller-domain>.<methodName>` 형식. controller class 의 `Controller` / `V1` suffix 제거.
- `ClusterController` → `cluster.createVmCluster`
- `ClusterKubernetesController` → `clusterKubernetes.delete`
- `ClusterKubeconfigImportController` → `clusterKubeconfigImport.importKubeconfig`
- `OperationController` → `operation.cancel`

**principal**:

1. `X-User-Id` header (gateway 가 검증된 사용자 식별자를 forward 합니다)
2. MDC 의 `principal` (인증 toggle 활성 시 SecurityFilter 가 채웁니다)
3. fallback: `anonymous`

---

## 응답 envelope

```json
{
  "success": true,
  "status": 200,
  "message": "Clusters loaded",
  "data": { "items": [ ... ] },
  "meta": {
    "requestId": "abc12345",
    "timestamp": "2026-05-11T03:45:21.123Z",
    "processingTimeMs": 42,
    "pagination": {
      "pageSize": 100,
      "nextPageToken": "eyJ2IjoibWV0YS5rOHMuaW8vdjEi...",
      "totalEstimate": null
    }
  },
  "links": {
    "self": "/v1/clusters/demo-aws-01",
    "operations": "/v1/clusters/demo-aws-01/operations",
    "helmReleases": "/v1/clusters/demo-aws-01/helm-releases",
    "events": "/v1/clusters/demo-aws-01/events"
  }
}
```

## Operation resource

```json
{
  "success": true,
  "status": 202,
  "message": "Cluster creation accepted",
  "data": {
    "id": "op-7f3a8c2e1b4d",
    "type": "CREATE_CLUSTER",
    "resourceType": "cluster",
    "resourceId": "demo-aws-01",
    "state": "RUNNING",
    "progress": {
      "currentStep": "BOOTSTRAP",
      "stepIndex": 2,
      "totalSteps": 3,
      "percent": 66
    },
    "startedAt": "2026-05-11T03:45:21Z",
    "createdAt": "2026-05-11T03:45:21Z"
  },
  "links": {
    "self": "/v1/operations/op-7f3a8c2e1b4d",
    "events": "/v1/operations/op-7f3a8c2e1b4d/events",
    "resource": "/v1/clusters/demo-aws-01"
  }
}
```

## HA control-plane (multi-master)

Pulumi config `anycloud-k8s:masterCount` 로 control-plane 노드 수를 지정합니다. 기본값은 `1`
(single master, legacy) 입니다. HA 는 etcd quorum 을 위해 **odd-only** (1/3/5/7) 만 허용하며, 짝수
입력은 400 을 반환합니다. 최댓값은 7 입니다.

```json
POST /v1/clusters
{
  "source": "vm",
  "clusterName": "demo-prox-ha",
  "spec": {
    "provider": "PROXMOX",
    "config": {
      "anycloud-k8s:masterCount": "3",
      "anycloud-k8s:workerCount": "3",
      ...
    }
  }
}
```

- `masterCount >= 2` 일 때 lead master 의 `kubeadm init` 에
  `--control-plane-endpoint=<leadIp>:6443` + `--upload-certs` 가 자동으로 추가됩니다.
- Extra master 들은 `kubeadm join --control-plane --certificate-key` 로 join 합니다.
- **PoC 한계**: control-plane endpoint 가 lead master IP 자체이며, VIP/LB 는 미적용
  lead master 장애 시 신규 join 이 불가능합니다 (기존 컴포넌트는 정상 동작합니다). 실제 HA 는 별도로 구성해야 합니다.
- Strategy 별 지원: GenericLinux (모든 deb-like) + Proxmox provisioner 입니다. 그 외 7
  providers (AWS, GCP, Azure, OCI, Alibaba, DigitalOcean, OpenStack) 는 multi-master 를 지원하지 않습니다.

## 표준 사용 흐름 — 신규 cluster 생성

```http
# 1. 카탈로그
GET /v1/providers/aws/regions
GET /v1/providers/aws/specs?region=ap-northeast-2

# 2. credential 선택
GET /v1/credentials

# 3. 사전 검증
POST /v1/cluster-validations
{ ... ProvisionDto ... }
→ 201 + 결과

# 4. 생성 (idempotent retry 안전)
POST /v1/clusters
Idempotency-Key: <uuid>
{ "vmGroupName": "demo-aws-01", "provider": "aws", "region": "ap-northeast-2", "credentialId": "cred-aws-001", "config": {...} }
→ 202 + Location: /v1/operations/op-7f3a + Operation body

# 5. 진행 추적 (SSE)
GET /v1/operations/op-7f3a/events
Accept: text/event-stream
event: progress
data: {"state":"RUNNING","progress":{"currentStep":"PROVISION","percent":33}}
event: progress
data: {"state":"RUNNING","progress":{"currentStep":"BOOTSTRAP","percent":66}}
event: succeeded
data: {"state":"SUCCEEDED","progress":{"percent":100}}

# 6. cluster 사용
GET /v1/clusters/demo-aws-01
GET /v1/clusters/demo-aws-01/namespaces/default/pods
PATCH /v1/clusters/demo-aws-01     # body={spec:{workerCount:5}} → scale
DELETE /v1/clusters/demo-aws-01    # → 202 Operation
```

---

## 운영 properties

본 표는 환경별로 override 가능한 설정 키이며, 모두 ENV 또는 `application.yaml` 에서 주입합니다.
| Property | Default | 설명 |
|---|---|---|
| `anycloud.audit.enabled` | `true` | false 면 `AuditInterceptor` bean 미등록 (slice 테스트 / 운영 비활성화) |
| `anycloud.idempotency.enabled` | `true` | false 면 `IdempotencyFilter` bean 미등록 |
| `anycloud.workflow.dlq-listener.enabled` | `true` | false 면 `DeadLetterListener` 미등록 |
| `anycloud.cors.allowed-origins` | _(empty → localhost fallback + warn)_ | 콤마 구분 explicit origin 리스트 |
| `anycloud.cors.allowed-methods` | `GET,POST,PUT,PATCH,DELETE,OPTIONS,HEAD` | 허용 method 리스트 |
| `anycloud.cors.max-age-seconds` | `3600` | preflight cache |
| `anycloud.http.insecure-tls` | `false` | true 면 모든 TLS cert 신뢰 (DEV ONLY, warn log) |
| `anycloud.http.connect-timeout-ms` | `10000` | RestTemplate connect timeout |
| `anycloud.http.request-timeout-ms` | `10000` | RestTemplate request timeout |
| `anycloud.helm.binary` | `helm` | helm CLI 경로 (`HELM_BINARY` ENV) |
| `anycloud.helm.exec-timeout` | `60s` | 일반 helm 명령 (`HELM_EXEC_TIMEOUT`) |
| `anycloud.helm.operation-timeout` | `5m` | install/upgrade/rollback/uninstall 의 `--timeout` |
| `anycloud.helm.long-exec-timeout` | `10m` | install/upgrade ProcessBuilder timeout |
| `csp-credential.encryption-key` | _(empty)_ | credential AES-GCM key. min 32 chars, sentinel 거부 |
| `security.auth.enabled` | `false` | gateway 가 인증 담당 시 false. true 면 token 필수 |
| `cluster.cert.expiry-check.cron` | `0 0 2 * * *` | cert scan cron (KST 11am ≒ UTC 2am) |
| `pulumi.backup.cron` | `0 0 3 * * *` | Pulumi state daily backup |
| `pulumi.backup.restore-dry-run.cron` | `0 30 3 * * *` | backup 검증 dry-run |
| `anycloud.idempotency.cleanup.cron` | `0 0 * * * *` | hourly cleanup of expired idempotency records |

### Caffeine cache

| Cache name | TTL | Max entries | 비고 |
|---|---|---|---|
| `vmOptions.regions` | 30m | 1000 | provider 별 regions |
| `vmOptions.specs` | 30m | 1000 | provider+region+keyword+gpuOnly+limit |
| `vmOptions.images` | 30m | 1000 | provider+region+keyword+architecture+owner+limit |

Prometheus metric: `cache.gets{cache=..., result=hit|miss}` / `cache.puts` / `cache.evictions`.

### ShedLock

`@Scheduled` 가 replica 여러 개에서 동시 실행되지 않도록 DB row lock 으로 leader election 을 수행합니다.

| Schedule | lockAtMostFor | lockAtLeastFor |
|---|---|---|
| `certExpiryScan` | 1h | 5m |
| `pulumiStateBackup` | 2h | 10m |
| `pulumiStateBackupValidate` | 1h | 10m |
| `idempotencyCleanup` | 10m | 1m |

Lock 테이블은 `shedlock` 입니다. Spring profile / replica 와 무관하게 활성화됩니다.

### DLQ listener

`vm-cluster.workflow.dlq` 에 적재된 메시지를 자동으로 consume + log + metric 처리합니다.

- Metric: `anycloud.workflow.dlq.received{originalQueue,reason}` (Counter)
- 권장 Prometheus 알람:
  ```promql
  increase(anycloud_workflow_dlq_received_total[5m]) > 0
  ```
- listener 자체 실패는 swallow 처리합니다 (무한 루프 방지). 메시지는 ack 후 폐기되며, 보존 정책이 필요하면 별도 DB row 에 저장한 뒤 ack 합니다.

### 보안 fail-fast

`CspCredentialCryptoServiceImpl` 부팅 시 검증을 수행합니다.
| key 상태 | 거동 |
|---|---|
| blank | warn log + 부팅은 진행 (credential 사용 시 런타임 실패) |
| `< 32 chars` | `IllegalStateException` → 부팅 실패 |
| sentinel (`change-me`, `anycloud-secret`, ...) | `IllegalStateException` → 부팅 실패 |

권장 설정은 다음과 같습니다.
```bash
export CSP_CREDENTIAL_ENCRYPTION_KEY=$(openssl rand -hex 32)
```
