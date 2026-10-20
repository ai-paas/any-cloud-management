# anycloud Bruno API collection (v1)

오픈소스 API 클라이언트 [Bruno](https://www.usebruno.com/) 기반 통합 테스트 컬렉션.
모든 endpoint 는 `/v1` prefix 입니다. 전체 명세는 [`docs/api/v1-reference.md`](../docs/api/v1-reference.md)
에 있습니다.

---

## TL;DR

```bash
# 1. CLI 설치
npm install -g @usebruno/cli

# 2. local 환경에서 Cluster CRUD 시나리오 전체 실행
cd .bruno/
bru run "Cluster (클러스터)" --env local

# 3. 특정 시나리오 (Helm 라이프사이클) 실행
bru run "Helm Repository (헬름 저장소)" --env local
bru run "Chart" --env local

# 4. 단일 요청
bru run "Cluster (클러스터)/2. Cluster Create (클러스터 생성).bru" --env local
```

`bru run "<folder>"` 는 폴더 안의 모든 `.bru` 를 `meta.seq` 순서대로 실행.
각 요청의 `script:post-response` 가 다음 요청에서 쓸 env var (`cluster_name`,
`helmRepo_name`, `last_operation_id` 등) 를 자동 promote 하므로 한 번에 이어서 실행할 수 있습니다.

---

## 시나리오 카탈로그

> VM 프로비저닝 시나리오 (VM Cluster / VM Credentials / VM Options / VM Workflow) 는
> 현재 별도 트랙으로 두고, 본 README 는 **운영 우선 6 개 시나리오** (A–F) 만 다룹니다.
> VM 트랙은 각 폴더 내 .bru 의 docs 블록 + `local-{aws,gcp,...}-provisioning.bru`
> 환경을 참고.

### A. Cluster CRUD (등록형 cluster 라이프사이클)

| seq | 요청 | endpoint | 비고 |
|---|---|---|---|
| 1 | List | `GET /v1/clusters` | 첫 결과 → `cluster_name` env |
| 2 | Create | `POST /v1/clusters` (registered) | 동기 201 + BootstrapInfo (helm/kubectl install 명령) |
| 3 | Info | `GET /v1/clusters/{name}` | 단건 조회 |
| 4 | Delete | `DELETE /v1/clusters/{name}` | source 자동 분기 (vm vs registered) |
| 5 | Test Connection | `POST /v1/clusters/{name}/connectivity-checks` | agent heartbeat 신선도로 판정 |

VM 인프라 자원 라이프사이클은 `/v1/vms` 네임스페이스에 따로 있습니다 (`VM Cluster (VM 클러스터)` 폴더).

**플로우: 신규 등록 → 검증 → 정리**
```
2 Create (CA/key/token 채워서) → 5 Test Connection → 3 Info → 4 Delete
```

### B. Helm Repository CRUD

| seq | 요청 | endpoint |
|---|---|---|
| 1 | List | `GET /v1/helm-repos` (응답 → `helmRepo_name` env) |
| 2 | Create | `POST /v1/helm-repos` |
| 3 | Info | `GET /v1/helm-repos/{name}` |
| 4 | Delete | `DELETE /v1/helm-repos/{name}` |

**플로우:** `2 Create → 1 List (promote name) → 3 Info → 4 Delete`

**기본 vars:** `helmRepo_name: chart-museum-external`

### C. Chart Lifecycle (카탈로그 → 배포 → 상태 → 제거)

| seq | 요청 | endpoint | LRO |
|---|---|---|---|
| 1 | Chart List | `GET /v1/helm-repos/{repo}/charts` | - |
| 2 | Chart Detail | `GET /v1/helm-repos/{repo}/charts/{chart}` | - |
| 3 | Chart Values | `GET /v1/helm-repos/{repo}/charts/{chart}/values?version=` | - |
| 4 | Chart README | `GET /v1/helm-repos/{repo}/charts/{chart}/readme?version=` | - |
| 5 | Deploy (JSON values 객체, **권장**) | `POST /v1/clusters/{c}/helm-releases` | 202 + Operation |
| 5a | Deploy (default values) | `POST /v1/clusters/{c}/helm-releases` body 에 values 생략 | 202 |
| 5b | Deploy (multipart file 업로드) | `POST /v1/clusters/{c}/helm-releases` (multipart) | 202 |
| 6 | Status | `GET /v1/clusters/{c}/helm-releases/{r}?namespace=` | - |
| 7 | Releases List | `GET /v1/clusters/{c}/helm-releases?namespace=` | - |
| 8 | Release Resources | `GET /v1/clusters/{c}/helm-releases/{r}/resources?namespace=` | - |
| 9 | Uninstall | `DELETE /v1/clusters/{c}/helm-releases/{r}?namespace=...` | 202 |
| 9b | Uninstall (history 보존 + wait) | `DELETE ...?keepHistory=true&wait=true` | 202 |
| z93 | Upgrade | `PUT /v1/clusters/{c}/helm-releases/{r}` | 202 + Operation |
| z94 | 적용된 values | `GET /v1/clusters/{c}/helm-releases/{r}/values` | 3번과 달리 배포본 기준 |

**플로우: 카탈로그 탐색 → 배포 → 정리**
```
1 List → 2 Detail → 3 Values → 5 Deploy
   → (Operations/4 SSE 로 진행 추적)
   → 6 Status → 7 Releases → 8 Resources
   → 9 Uninstall
```

**기본 vars:** `repo_name: chart-museum-external`, `chart_name: ingress-nginx`,
`release_name: nginx-test-release`,
`namespace: default`, `version: 4.15.1`

### D. Kubernetes 리소스 조회/삭제

| seq | 요청 | endpoint |
|---|---|---|
| 1 | Resource List (namespaced) | `GET /v1/clusters/{c}/namespaces/{ns}/{kind}?pageSize=100` |
| 2 | Resource Info | `GET /v1/clusters/{c}/namespaces/{ns}/{kind}/{name}` |
| 3 | Resource Delete | `DELETE /v1/clusters/{c}/namespaces/{ns}/{kind}/{name}` |
| 4 | Nodes List (cluster-scoped) | `GET /v1/clusters/{c}/namespaces/-/nodes` |
| 5 | Namespaces List | `GET /v1/clusters/{c}/namespaces/-/namespaces` |
| 6 | PVs List | `GET /v1/clusters/{c}/namespaces/-/persistentvolumes` |
| 7 | StorageClasses List | `GET /v1/clusters/{c}/namespaces/-/storageclasses` |
| 8 | CRDs List | `GET /v1/clusters/{c}/namespaces/-/customresourcedefinitions` |
| z95 | 전체 노드 목록 | `GET /v1/nodes` (cluster 를 가로질러 조회) |

cluster-scoped kind 도 같은 경로 패턴을 씁니다. namespace 자리에 `-` 를 넣어 구분하고, 서버는
cluster-scoped kind 면 그 값을 무시합니다. `_all` 은 전체 namespace 조회입니다.

`kind` 는 plural 입니다 (pods, services, deployments, configmaps). `pageSize` 와 `continueToken`
으로 페이지를 넘기고, 응답의 `data.continueToken` 이 null 이 아니면 다음 페이지가 있습니다.

**기본 vars:** `namespace: default`,
`resource_kind: pods`, `resource_name: podinfo-778b865b7b-7vj9j`

### E. Monitoring

| seq | 요청 | endpoint |
|---|---|---|
| 1 | NodeStatus | `GET /v1/clusters/{c}/node-metrics` |
| 2 | resourceMonit | `GET /v1/clusters/{c}/resource-metrics/{type}/{key}` |

`type ∈ {pod, node, namespace, deployment}`, `key = namespace/name` (pod, deployment)
또는 `name` (node, namespace).

### F. Operations (LRO 추적)

| seq | 요청 | endpoint | 비고 |
|---|---|---|---|
| 1 | Search | `GET /v1/operations?state=RUNNING&pageSize=20` | filter: state, resourceType |
| 2 | Get | `GET /v1/operations/{id}` | progress + state |
| 3 | Cancel | `POST /v1/operations/{id}:cancel` | RUNNING/PENDING 만 가능 |
| 4 | Events (SSE) | `GET /v1/operations/{id}/events` | 실시간 진행률 stream |
| z90 | 자원 변경 SSE | `GET /v1/events` | 바뀌었다는 신호만. 값은 다시 조회해야 합니다 |

**플로우: LRO 추적**
```
(이전 요청 응답이 last_operation_id 자동 promote)
  → 4 Events 로 SSE 추적 (Bruno 응답 뷰는 부분만 표시; curl 권장)
  → 또는 2 Get 으로 polling
  → 필요 시 3 Cancel
```

curl SSE 예시:
```bash
curl -N -H 'Accept: text/event-stream' \
     http://localhost:8888/v1/operations/op-7f3a/events
```

### G. 자격증명, 카탈로그, Fleet

| 폴더 | 요청 | endpoint |
|---|---|---|
| VM Options | z92 | `GET /v1/providers/{p}/credential-schema` |
| VM Options | z93 | `GET /v1/providers/provisioning-defaults` |
| VM Credentials | 3b | `GET /v1/credentials/{id}/reveal` |
| VM Credentials | 3c | `POST /v1/credentials/{id}/health/refresh` |
| VM Credentials | 3d | `PATCH /v1/credentials/{id}` |
| Admin | z99 | `POST /v1/fleet/upgrade` |
| Admin | z100 | `POST /v1/fleet/upgrade/{runId}/abort` |
| VM Cluster | 2b0 | `POST /v1/vms/preflight` |

`z93` 은 CSP 별 생성 기본값과 준비 상태를 돌려줍니다. Proxmox 는 고정 IP 네 값(`nodeIps`,
`gateway`, `sshHost`, `sshPorts`)이 채워져 있어야 일괄 생성이 주소를 되묻지 않습니다.

`2b0` 과 `2a` 는 결과 모양이 같지만 요청 본문이 다릅니다. `2a` 는 평평한 `ProvisionClusterRequest`,
`2b0` 은 `spec` 을 중첩한 `VmCreateRequest` 를 받습니다.

---

## CLI 자동 실행 (`bru run`)

```bash
# 시나리오 전체 (폴더 단위)
bru run "Cluster (클러스터)"      --env local
bru run "Helm Repository (헬름 저장소)" --env local
bru run "Chart"                     --env local
bru run "Kubernetes (쿠버네티스)"   --env local
bru run "Monitoring (모니터링)"      --env local
bru run "Operations (LRO)"         --env local

# 시나리오 체이닝: A → B → C 순차
bru run "Cluster (클러스터)" --env local && \
  bru run "Helm Repository (헬름 저장소)" --env local && \
  bru run "Chart" --env local

# Reporter 옵션 (CI 적합)
bru run "Chart" --env local --reporter-json results.json
bru run "Chart" --env local --reporter-junit junit.xml

# 단일 요청
bru run "Cluster (클러스터)/2. Cluster Create (클러스터 생성).bru" --env local

# 환경 override (env file 의 값을 CLI 인자로 덮어쓰기)
bru run "Cluster (클러스터)" --env local --env-var cluster_name=my-test-001
```

**CI 통합 팁:**
1. `bru run <folder> --reporter-junit junit.xml` → CI 의 JUnit 파서로 결과 시각화
2. exit code 0 = 모든 test 통과; 비정상 종료시 마지막 실패 요청의 stdout 확인
3. `--insecure` 는 self-signed cert 환경에서만 씁니다. production 에서는 쓰지 마세요

---

## v1 공통 응답 envelope

```json
{
  "success": true, "status": 200, "message": "...",
  "data": { ... },
  "meta": {
    "requestId": "abc12345",
    "timestamp": "2026-05-18T03:45:21Z",
    "processingTimeMs": 42,
    "pagination": { "pageSize": 100, "nextPageToken": "..." }
  },
  "links": { "self": "/v1/...", "operations": "/v1/.../operations", "events": "..." }
}
```

list 응답은 `data.items` 가 본문, `meta.pagination.nextPageToken` 으로 다음 페이지.

## Operation resource (LRO)

POST/PATCH/DELETE 가 비동기인 경우 모두 Operation 자원 반환:

- `Location: /v1/operations/op-xxx` 헤더
- body 의 `data` 는 Operation (id/type/state/progress)
- `links.events` = SSE URL

Operation id 는 각 요청의 `script:post-response` 가 자동으로 `last_operation_id` env var 에
저장하므로 `Operations/2`, `Operations/3`, `Operations/4` 에서 그대로 재사용합니다.

## Idempotency-Key

모든 POST/PATCH/DELETE 요청에 자동 첨부:
```
Idempotency-Key: {{$randomUUID}}
```
24h 내 같은 key 로 재시도 → 첫 응답 그대로 재현 (네트워크 hiccup 안전).
같은 key + 다른 body → 409 Conflict.

## Variable Strategy

- **Environment variables** (`environments/local.bru`): 환경별 baseUrl, region, provider
- **Request `vars:pre-request`**: 요청별 기본값. 단독 실행해도 바로 돌아갑니다
- **Post-response promote**: `script:post-response` 가 다음 요청에 쓸 값을 env 로 저장합니다
- **Bruno dynamic vars**: `{{$randomUUID}}`, `{{$isoTimestamp}}`, `{{$timestamp}}`

우선순위: `Runtime > Request > Folder > Environment > Collection > Global`

자동 promote 되는 env var:
| 출처 | 변수 |
|---|---|
| Cluster List / Create | `cluster_name`, `last_operation_id` |
| HelmRepo List | `helmRepo_name` |
| Chart Deploy / Uninstall | `last_operation_id` |
| VM Credential Create | `vm_credential_id` |
| Monitoring NodeStatus | `cluster_name` (응답 첫 row) |
| Fleet Upgrade 실행 | `fleet_upgrade_run_id` |

## Recommended environments

- `local`: 기본 로컬 (`baseUrl=http://localhost:8888`)
- `local-{aws,gcp,alibaba,oci,openstack,proxmox,ibm}-provisioning`: CSP 7종별 VM 프로비저닝 quick-switch

### VM Cluster 트랙: CSP 별 quick-start

VM 프로비저닝은 CSP 마다 필요한 config 키가 달라 **provider 별 .bru 가 따로 있습니다** (`2b` ~ `2m`).
`2. VM Cluster Create` 는 모든 키를 한 body 에 넣은 참고용이라, 실제로는 본인 CSP 에 맞는 파일을
쓰세요.

```bash
# 1. credential 등록 (한 번만)
bru run "VM Credentials (VM 자격증명)/2b. AWS Credential Create.bru" \
    --env local-aws-provisioning
# 응답에서 id 받아 환경의 credential_id 에 채움 (자동 promote 됨)

# 2. 사전 검토 (선택). credential, quota, 비용을 미리 봅니다
bru run "VM Cluster (VM 클러스터)/2a. VM Cluster Preflight.bru" \
    --env local-aws-provisioning

# 3. CSP 별 cluster 생성
bru run "VM Cluster (VM 클러스터)/2b. AWS Cluster Create.bru" \
    --env local-aws-provisioning
```

| Provider | 파일 | 추가 필수 config |
|----------|------|------------------|
| AWS | `2b. AWS Cluster Create` | (없음) |
| GCP | `2c. GCP Cluster Create` | `gcp_project` |
| Alibaba | `2e. Alibaba Cluster Create` | `alibaba_zone` |
| OpenStack | `2f. OpenStack Cluster Create` | `openstack_image_name`, `openstack_flavor_name`, `openstack_external_network_id`, `openstack_floating_ip_pool` |
| OCI | `2h. OCI Cluster Create` | `oci_compartment_id` |
| Proxmox | `2k. Proxmox Cluster Create` | `proxmox_node_name` |
| IBM | `2m. IBM Cluster Create` | `ibm_zone` |

자세한 사용법: `.bruno/VM Cluster (VM 클러스터)/folder.bru` 의 docs 블록.

## Troubleshooting

| 증상 | 원인 / 해결 |
|---|---|
| `400 NO_BODY` | body 가 비었거나 JSON 파싱 실패. Bruno 의 `body:json` 블록 확인. 큰 values 는 `5b. multipart` 사용. |
| `409 Conflict` (Idempotency) | 같은 `Idempotency-Key` 로 다른 body 재전송. 새 요청은 `{{$randomUUID}}` 라 자동 갱신되므로 보통 환경 변수에 고정값을 박은 경우. |
| `404 cluster not found` (DELETE/GET) | `cluster_name` env 가 stale. `Cluster List` 한 번 돌리거나 default 갱신. |
| `Failed to decrypt CSP credential payload` | 백엔드 AES key 변경됨. credential 재등록 필요 (`VM Credentials/2~2j`). |
| `Chart README ENTITY_NOT_FOUND` | 이전 버전 backend 의 stderr 오탐입니다. 백엔드를 최신으로 올리세요. |
| SSE 응답이 끊김 | Bruno 표준 뷰는 SSE 부분만 표시. `curl -N` 으로 직접 확인. |
