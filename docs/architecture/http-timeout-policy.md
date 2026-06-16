# HTTP Timeout Policy

anycloud backend 의 HTTP 호출 timeout 정책. 모든 HTTP client 가 명시 timeout 보유 — 운영 시
backend 가 외부 system slow response 에 의해 stuck 되는 위험 차단.

## 1. Timeout 정책 표

| Client | 용도 | Connect timeout | Read / Response timeout | Property |
|---|---|---|---|---|
| `restTemplate` (메인) | Helm chart download / 외부 HTTPS | 10s | 10s | `anycloud.http.connect-timeout-ms`, `anycloud.http.request-timeout-ms` |
| `cspRestTemplate` (CSP API) | CSP metadata 조회 (region/spec/image) | 3s | 5s | `anycloud.csp.http.connect-timeout-ms`, `anycloud.csp.http.read-timeout-ms` |
| AWS SDK | EC2 / VPC / IAM API | n/a | API call 8s, attempt 3s | code-level (AwsVmOptionsProvider) |
| Webhook `HttpClient` (java.net.http) | 외부 portal event publish | `webhook.timeoutMs` | `webhook.timeoutMs` | `webhook.timeoutMs` (default 5000ms) |
| gRPC (cluster-agent reverse-tunnel) | agent ↔ backend stream | n/a (long-lived) | n/a (streaming) | `anycloud.agent.grpc.*` |
| pulumi CLI subprocess | Pulumi up/preview/destroy | n/a (process) | `anycloud.pulumi.*-timeout` | `cluster-provisioning-starter` |

## 2. 설계 원칙

### 짧은 timeout (3-5s) = 빠른 fail
- CSP metadata API — 보통 100-500ms 응답. 5s 초과면 CSP outage 신호 → 빠른 fail
- 운영자 UI 의 cluster create form 의 region/spec dropdown — 느린 응답 = UX 영향

### 중간 timeout (8-10s) = balanced
- Helm chart download — chart 크기 변동성. 10s 면 일반 chart OK, 큰 chart fail
- AWS SDK call — IAM eventually consistent 의 backoff 고려

### 긴 timeout (수십 분) = workflow level
- Pulumi up — VM 생성 + bootstrap = 5-10분 정상. timeout 30분 권장
- gRPC long-lived stream — timeout 없음, heartbeat 정책으로 staleness detect

## 3. property override 정책

```yaml
# application-{env}.yaml 의 override 가능
anycloud:
  http:
    connect-timeout-ms: 10000        # 메인 RestTemplate (Helm 등)
    request-timeout-ms: 10000
  csp:
    http:
      connect-timeout-ms: 3000        # CSP metadata 조회
      read-timeout-ms: 5000
  pulumi:
    up-timeout: 30m                   # Pulumi up
    preview-timeout: 5m
  agent:
    grpc:
      heartbeat-interval: 30s         # gRPC stream staleness

webhook:
  timeout-ms: 5000                    # webhook HTTP (java.net.http)
  max-attempts: 3
  initial-interval-ms: 500
```

## 4. Retry vs Timeout

| 호출 유형 | Timeout | Retry | Backoff |
|---|---|---|---|
| RestTemplate (메인) | 10s | 0 (실패 시 즉시 사용자에게 전달) | n/a |
| CSP API | 5s | 0 (다음 사용자 호출 시 재시도 — cached) | n/a |
| Webhook | 5s | 3회 | exponential (500ms × 2^n) |
| Pulumi up | 30m | RabbitMQ workflow level retry | RabbitMQ retry-policy (maxAttempts=3) |
| gRPC stream | n/a | reconnect on disconnect | exponential backoff |

## 5. Monitoring + alert

운영 환경에서 다음 metric 권장:

| Metric | Threshold | Alert |
|---|---|---|
| `http_client_seconds{client="restTemplate"} p99` | > 8s | warning (timeout 임박) |
| `http_client_seconds{client="cspRestTemplate"} p99` | > 4s | warning |
| `webhook_publish_duration_seconds p95` | > 3s | warning |
| `pulumi_command_duration_seconds{op="up"} p95` | > 15m | warning |

## 6. 변경 시 영향 검토

timeout 변경 시 다음 영향 검토 필수:
- **단축**: 진행 중 호출이 fail 가능 — retry 정책 검토
- **연장**: 진행 중 backend resource hold 시간 ↑ — virtual thread / async pool 크기 검토

## 7. anti-pattern (회피)

| 패턴 | 문제 |
|---|---|
| Hard-coded timeout in code | property override 불가 |
| Timeout 무제한 (또는 매우 큼) | backend stuck risk |
| 모든 호출 동일 timeout | CSP API (빠름) vs Helm download (느림) — 차별화 안 됨 |
| Timeout 짧고 retry 없음 | 일시적 network blip → 사용자 에러 |
