# Grafana dashboards

anycloud backend 의 운영 metric dashboard 모음. Prometheus 가 `/actuator/prometheus` 를 scrape 하는 것을 전제로.

## 목록

- `anycloud-csp-resilience.json` — CSP API resilience (III-44)
  - `validateLive` p50/p95/p99 by provider
  - validateLive failure rate (5m window)
  - CircuitBreaker `csp-api` state / failure rate / slow call rate
  - CB calls (successful/failed/rejected/slow)
  - Operation start rate
  - Pulumi bulkhead 사용량

## Import 방법

1. Grafana → Dashboards → Import
2. 본 JSON 의 내용 붙여넣기 또는 file upload
3. Prometheus datasource 선택 (`uid: prometheus`)

## Alert 룰 (별도 sprint)

dashboard 의 thresholds 가 cue:
- validateLive p95 > 10s → CSP API 장애 의심
- failure rate > 50% (5m) → credential 또는 region 문제
- CB OPEN 진입 → 자동 알림 + 5m 후 HALF_OPEN 자동 시도

## Metric 출처

| Metric | 출처 | 비고 |
|---|---|---|
| `anycloud_csp_validate_live_seconds_*` | `ProvisioningProviderValidator.validateLive` | provider/outcome tag |
| `resilience4j_circuitbreaker_*` | resilience4j auto-instrumentation | name tag = "csp-api" |
| `resilience4j_bulkhead_*` | resilience4j auto-instrumentation | name tag = "pulumi" |
| `anycloud_operation_started_total` | OperationServiceImpl | type tag |
