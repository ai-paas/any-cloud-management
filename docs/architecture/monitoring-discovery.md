# Monitoring URL Discovery

cluster-agent 가 in-cluster 에서 prometheus service 를 auto-discover 하고, backend 는 `QUERY_METRICS`
RPC 로 위임합니다. backend 는 cluster 내부 service 에 한 번도 reach 하지 않습니다.

## 구조

```
┌─────────────┐  QUERY_METRICS RPC      ┌─────────┐  HTTP GET prometheus  ┌────────────┐
│   Backend   │ ──────────────────────► │  Agent  │ ───────────────────► │ prometheus │
│ (container) │  (gRPC, agent dial-in)  │ (in-pod)│  (in-cluster Service) │  Service   │
└─────────────┘ ◄────────────────────── └─────────┘                        └────────────┘
                  PromQLResult.raw                                          (kube-prom-stack
                                                                            또는 user-managed)
```

Cluster 등록 body 에 `monitServerURL` 필드가 없습니다 — agent 가 in-cluster service 를 auto-discovery 합니다.

## Discovery 알고리즘

`apps/agent/internal/controller/observability.go` 의 `discoverPrometheusURL(ctx, dispatcher, ns)` 는 다음과 같습니다.

```
1) Cache lookup (5분 TTL)
     ↳ Hit  → 캐시 URL 반환
     ↳ Miss → 2)

2) Label-based Service LIST in ns (default="monitoring")
     ↳ selector "app.kubernetes.io/name=prometheus" 우선
     ↳ 매칭 0개면 "prometheus.io/scrape=true" 재시도
     ↳ 매칭된 Service 중:
        - ClusterIP 있어야 함 (None / "" 제외)
        - Port name == "web" | "http-web" | "http" 또는 port == 9090 우선
        - 없으면 첫 port fallback
     ↳ 결과 URL 캐시 후 반환

3) Fallback — hardcoded kube-prometheus-stack DNS
     ↳ "http://kube-prometheus-stack-prometheus.<ns>.svc:9090"
```

### 캐시 정책

- TTL 은 5분입니다.
- Key 는 implicit 입니다 (process-wide single URL). 다중 prometheus 지원 시 ns 별 캐시로 확장이 필요합니다.
- Invalidation 은 TTL only 입니다 — agent 재기동 시 cold start 합니다.

### Discovery 가 0개 매칭 시

Fallback URL 이 사용되며, agent 의 실제 HTTP 호출이 503/timeout 으로 fail 합니다. Backend 에 `PROM_QUERY_FAILED`
또는 `PROM_NON_2XX` 응답이 반환됩니다.

## Backend 호출 흐름 (MonitoringServiceImpl)

```java
// 1) cluster 존재만 검증
assertClusterExists(clusterName);

// 2) cluster-observability starter 의 ObservabilityQueryService 호출
PromQLResult res = observabilityQueryService.queryInstant(
    clusterName, promql, time, timeout);

// 3) PromQL response 의 raw JSON parse → data.result 부분 추출
JsonNode root = objectMapper.readTree(res.raw());
return root.path("data").path("result");
```

## 사용자 영향

| 항목 | 동작 |
|---------|-------|
| Cluster 등록 body | `monitServerURL` 필드 입력 X 입니다 — agent 가 자동으로 처리합니다. |
| Prometheus URL 변경 | Agent cache TTL 만료 후 자동 재발견합니다. |
| Custom Prometheus | label `prometheus.io/scrape=true` 부착으로 자동 감지합니다. |

## Limitations

1. **Single cache slot** — process 전역 1개 URL 입니다. 여러 prometheus 가 있으면 첫 매칭을 사용합니다.
   ns 별 캐시로 확장 시 캐시 키는 ns 가 됩니다.
2. **TTL=5분 hard-coded** — prometheus 가 자주 재배포되면 latency 가 발생합니다. config 노출은 미구현 상태입니다.
3. **No proactive invalidation** — Watch / Informer 로 service 변경 즉시 갱신은 미구현 상태입니다.

## 검증 방법

```bash
# (1) 클러스터에 prometheus-stack 있는 상태에서 metric 조회
curl 'http://localhost:8888/v1/clusters/<name>/resource-metrics/cpu/usage?namespace=aipaas-system'

# (2) agent log 에서 discover 결과 확인
kubectl logs -n aipaas-system -l app.kubernetes.io/name=cluster-agent | grep prometheus

# (3) 캐시 TTL 검증 — 5분 후 prometheus service 변경 시 자동 재발견
kubectl label svc -n monitoring prometheus-stack-kube-prom-prometheus app.kubernetes.io/name-
# 5분 후 동일 metric 조회 → fallback 또는 다른 label 매칭으로 자동 전환
```

## 관련

- Cluster Entity 에는 `monit_server_url` + admin 자격 컬럼이 없습니다 (DB 정리됨).
- POST `/v1/clusters` 응답에 bootstrap token + helm install command 가 즉시 노출됩니다.
- GET `/v1/clusters/{id}/agent-manifest.yaml` — ready-to-apply YAML 을 발급합니다.
