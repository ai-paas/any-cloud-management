# Monitoring API 사용 가이드

kube-prometheus-stack install 부터 PromQL/대시보드/alert 운영 전반의 가이드입니다. install path 는 `cluster_addon` 으로 통합되어 있습니다.

## 빠른 시작

### 1. Monitoring stack 설치 (kube-prometheus-stack)

cluster 가 ACTIVE 면 다음 한 번의 호출로 Prometheus + Grafana + Alertmanager 가 자동 install 됩니다.

```http
POST /v1/clusters/{cluster_name}/addons
Content-Type: application/json

{
  "type": "MONITORING",
  "catalogId": "kube-prometheus-stack",
  "chartVersion": "65.0.0",
  "enabled": true
}
```

응답 (202 Accepted):
```json
{
  "data": {
    "id": "addon-<uuid>",
    "state": "ENQUEUED",
    "lastOperationId": "op-<uuid8>",
    ...
  }
}
```

진행 상태는 SSE 로 구독합니다.
```http
GET /v1/operations/{operationId}/events     # SSE stream — state/percent push
```

또는 다음 단축 lookup 을 사용합니다.
```http
GET /v1/clusters/{c}/addons/{addonId}/operation   # 최신 operationId 반환
```

state 가 `SUCCEEDED` 가 되면 PromQL 호출 + Grafana 접속이 가능합니다.

### 2. PromQL 으로 metric 조회 (frontend 가 매번 호출하는 1차 API)

**Instant query** — 현재 시점 값입니다.

```http
GET /v1/clusters/{cluster_name}/metrics/query?query=up
```

응답은 Prometheus envelope **raw passthrough** 입니다 — backend wrapping 이 없습니다.
```json
{
  "status": "success",
  "data": {
    "resultType": "vector",
    "result": [ { "metric": {...}, "value": [ <ts>, "<val>" ] }, ... ]
  }
}
```

**Range query** — 시계열 (그래프용) 입니다.

```http
GET /v1/clusters/{c}/metrics/query_range?query=...&start=<ts>&end=<ts>&step=30s
```

응답: `resultType=matrix`, 각 series 는 `[[ts, val], ...]` 입니다.

#### 추가 optional param (Prometheus 표준)

| param | 의미 | 예시 |
|-------|------|------|
| `time` | instant query 시점 (RFC3339 또는 unix) | `time=2026-06-08T12:00:00Z` |
| `timeout` | 서버측 시간 제한 | `timeout=30s` |
| `limit` | 결과 row 제한 | `limit=100` |
| `lookback_delta` | staleness lookback | `lookback_delta=5m` |
| `stats` | `all` 이면 query stats 포함 | `stats=all` |

#### Param alias

`query` 가 표준이지만 `promql` 도 backward-compat alias 로 수용합니다. 둘 다 비어있으면 `MISSING_QUERY` (400) 입니다.

### 3. Grafana 접속

```http
GET /v1/clusters/{c}/observability/dashboard
```

응답에서 `url` 을 사용자에게 forward 합니다 — Ingress > LoadBalancer 우선순위로 자동 결정됩니다. 외부 노출이 없으면 `GRAFANA_NOT_EXPOSED` (404) 입니다.

### 4. Active Alert 조회

```http
GET /v1/clusters/{c}/observability/alerts
```

응답의 `raw` 에 Alertmanager `/api/v2/alerts` JSON 이 그대로 들어 있습니다.

---

## PromQL Cookbook — 자주 쓰는 식

frontend 가 위 `GET /metrics/query[+_range]` 의 `query` param 에 다음 식을 그대로 넣어 사용합니다.

### 노드 CPU 사용률 (idle 제외)
```promql
(1 - avg by (node) (rate(node_cpu_seconds_total{mode="idle"}[5m]))) * 100
```

### 노드 메모리 사용 bytes (= MemTotal − MemAvailable)
```promql
node_memory_MemTotal_bytes - node_memory_MemAvailable_bytes
```

### Namespace 별 CPU 사용 cores
```promql
sum by (namespace) (rate(container_cpu_usage_seconds_total{container!=""}[5m]))
```

### Namespace 별 메모리 사용 bytes
```promql
sum by (namespace) (container_memory_working_set_bytes{container!=""})
```

### Pod phase 분포 (Running / Pending / Failed / Succeeded / Unknown)
```promql
sum by (phase) (kube_pod_status_phase)
```

### TopK CPU 노드
```promql
topk(5, (1 - avg by (node) (rate(node_cpu_seconds_total{mode="idle"}[5m]))) * 100)
```

### Pod restart 횟수 (1시간 내)
```promql
sum by (namespace, pod) (increase(kube_pod_container_status_restarts_total[1h]))
```

### Disk 사용률 (% — root volume)
```promql
100 - ((node_filesystem_avail_bytes{mountpoint="/"} * 100) / node_filesystem_size_bytes{mountpoint="/"})
```

### GPU utilization (dcgm-exporter 설치 필요)
```promql
DCGM_FI_DEV_GPU_UTIL
```

### Cluster 전체 fan-out (모든 cluster 동시 조회)
```http
GET /v1/observability/aggregate?promql=up
```
응답: `{cluster_name → PromQLResult}` map 입니다.

---

## 운영 / Debug Path

### Scrape target 상태 확인

Prometheus 가 어떤 target 을 긁고 있는지, scrape error 가 어떤 target 인지 확인합니다.
```http
GET /v1/clusters/{c}/observability/targets
GET /v1/clusters/{c}/observability/targets?state=active
GET /v1/clusters/{c}/observability/targets?state=dropped
```
응답의 `raw` 에 Prometheus `/api/v1/targets` JSON 이 들어 있습니다. 운영 debug 용 — UI 에는 노출하지 않습니다.

### Alert silence (alertmanager)

활성 alert 을 임시로 silence 처리합니다 (예: 정기 점검 중 알람 차단).

```http
GET    /v1/clusters/{c}/observability/alert-silences       # 목록
POST   /v1/clusters/{c}/observability/alert-silences       # 생성
DELETE /v1/clusters/{c}/observability/alert-silences/{id}  # 삭제
```

POST body 예시:
```json
{
  "matchers": "[{\"name\":\"alertname\",\"value\":\"HighCPU\",\"isRegex\":false}]",
  "startsAt": "2026-06-08T10:00:00Z",
  "endsAt": "2026-06-08T12:00:00Z",
  "createdBy": "admin@example.com",
  "comment": "정기 점검 — 1시간 silence"
}
```

### Alert Rule (PrometheusRule) 카탈로그

starter 가 bundle 한 default rule-set 입니다.
```http
GET  /v1/observability/alert-rules                                       # 카탈로그
POST /v1/clusters/{c}/observability/alert-rules/{ruleSetId}              # 단일 설치
POST /v1/clusters/{c}/observability/alert-rules/install-all              # 전체 설치
DELETE /v1/clusters/{c}/observability/alert-rules/{ruleSetId}            # 제거
```

monitoring addon install 직후 `MonitoringAddonInstaller.onAfterInstall` 가 자동으로 `installAll` 을 호출합니다.

---

## Frontend 통합 패턴

```javascript
// 1. cluster 생성 시 addon checkbox
const addons = userSelectedCheckboxes(); // [{type, catalogId}, ...]
await POST('/v1/clusters', { ..., spec: { ..., addons } });

// 2. cluster ACTIVE 후 addon 추가 (또는 cluster create 시 자동 enqueue)
const { data } = await POST(`/v1/clusters/${c}/addons`, {
  type: 'MONITORING',
  catalogId: 'kube-prometheus-stack',
});

// 3. SSE 로 install progress 추적
const op = await GET(`/v1/clusters/${c}/addons/${data.id}/operation`);
new EventSource(`/v1/operations/${op.data.id}/events`)
  .addEventListener('state', (e) => updateProgress(JSON.parse(e.data)));

// 4. install 완료 후 metric 조회 — 매번 PromQL 직접
const cpu = await GET(`/v1/clusters/${c}/metrics/query?query=${encodeURIComponent(promql)}`);

// 5. Grafana 링크
const dash = await GET(`/v1/clusters/${c}/observability/dashboard`);
window.open(dash.data.url);

// 6. Alert 표시
const alerts = await GET(`/v1/clusters/${c}/observability/alerts`);
const parsed = JSON.parse(alerts.data.raw); // Alertmanager raw
```

---

## Error Code 매핑

| HTTP | 의미 | 원인 |
|------|------|------|
| 400 | `INVALID_INPUT_VALUE` | spec 잘못 (chart 미명시, unknown catalogId, 등) |
| 400 | `MISSING_QUERY` | `query` / `promql` 둘 다 비어있음 |
| 403 | `CHART_NOT_ALLOWED` | agent allowlist 에 chart 미등록 |
| 404 | `ENTITY_NOT_FOUND` | cluster / addon id 없음 |
| 404 | `GRAFANA_NOT_EXPOSED` | Ingress / LoadBalancer 미설정 |
| 409 | `INVALID_INPUT_VALUE` | state 충돌 (FAILED 외에 retry 호출, duplicate addon 등) |
| 502 | `AGENT_CALL_FAILED` | agent gRPC 에러 |
| 503 | `NO_ACTIVE_AGENT` | cluster-agent stream 미연결 |
| 504 | `TIMEOUT` | agent 응답 지연 |

---

## 참고 자료

- `apps/anycloud/src/main/resources/config/addons.yaml` — addon catalog YAML
- `apps/anycloud/src/main/java/com/aipaas/anycloud/domain/addon/` — addon 서비스 layer
- `.bruno/Monitoring (모니터링)/` — Bruno collection (Query / Range / aggregate / alerts / dashboard)
- `.bruno/Addons (애드온)/` — addon CRUD .bru
