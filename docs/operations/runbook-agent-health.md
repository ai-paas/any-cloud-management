# Runbook — Cluster Agent Health

운영자가 `AnycloudClusterAgentUnhealthy`, `AnycloudAgentHeartbeatStale`, `AnycloudAgentStatusFailedOrRevoked`, `AnycloudFleetDegraded`, `AnycloudAgentStreamDown` alert 를 받았을 때의 점검 흐름입니다.

## 0. 빠른 점검 — 어디서 무엇이 망가졌는가

```
GET /v1/agents/health           # fleet 전체 summary
GET /v1/clusters/{name}/health  # 단일 cluster 상세 (healthy / summary / agentStatus / streamActive / lastSeenSecondsAgo)
```

- `healthy=true` → false alarm 가능 (alert 가 stale data 로 발사된 경우입니다)
- `healthy=false` + `summary` 확인:
  - `"no agent registered yet"` → cluster 에 agent 미설치 (의도적일 수 있습니다)
  - `"agent status=DEGRADED/FAILED/REVOKED"` → cluster_agent 테이블 status 가 비정상입니다
  - `"agent ACTIVE in store but no live stream"` → backend restart 후 reconnect 가 안 됩니다
  - `"heartbeat stale (Xs ago)"` → agent process 는 살아있지만 신호가 안 옵니다

Grafana dashboard `Anycloud — Cluster Agent Fleet Health` (UID `anycloud-cluster-agent-health`) 의 *Per-cluster current status* 테이블이 같은 정보를 시각화합니다.

## 1. AnycloudClusterAgentUnhealthy — 단일 cluster 5분 이상 unhealthy

### 점검 순서

1. **Health endpoint 확인** (위 §0 참조)
2. **cluster_agent DB row 확인**
   ```sql
   SELECT cluster_name, agent_instance_id, status, last_seen_at, last_k8s_api_ok_at, last_error, revoked_at
   FROM cluster_agent WHERE cluster_name = '<NAME>';
   ```
   - `status` 가 ACTIVE 인지 확인합니다.
   - `last_seen_at` 이 최근인지 (heartbeat 도착 시각) 확인합니다.
   - `last_error` 에 직전 실패 메시지가 있는지 확인합니다.
   - `revoked_at` 이 NULL 인지 확인합니다 (NULL 이 아니면 token 이 폐기된 것입니다 — §3 참조).
3. **Agent pod 상태**
   ```bash
   kubectl -n aipaas-system get pods -l app=cluster-agent
   kubectl -n aipaas-system describe pod <pod-name>
   ```
   - `CrashLoopBackOff` / `ImagePullBackOff` / `Pending` → pod 자체 문제입니다.
   - `Running` 인데 alert 인 경우 — 다음 단계로 진행합니다.
4. **Agent logs**
   ```bash
   kubectl -n aipaas-system logs -l app=cluster-agent --tail=200
   ```
   - 자주 나오는 패턴:
     - `GRPC_RECONNECT_FAILED` → backend 접근 불가 (DNS / network / TLS)
     - `BOOTSTRAP_TOKEN_EXPIRED` → registration token 만료
     - `IDENTITY_TOKEN_INVALID` → identity_token_hash mismatch
     - `K8S_API_UNAVAILABLE` → agent → API server 못 가 (RBAC / network)

### 흔한 복구

| 증상 | 조치 |
|---|---|
| Pod CrashLoopBackOff with image pull error | image pull secret 확인, `kubectl -n aipaas-system get events` |
| `GRPC_RECONNECT_FAILED` | agent env `BACKEND_GRPC_ENDPOINT` 가 cluster 안에서 reach 되는 DNS 인지 확인 |
| `K8S_API_UNAVAILABLE` | agent ServiceAccount 의 ClusterRoleBinding 확인 (chart 의 `rbac.yaml`) |
| `BOOTSTRAP_TOKEN_EXPIRED` | bootstrap token 재발급 후 `helm upgrade` (or raw manifest 재적용) |

## 2. AnycloudAgentHeartbeatStale — heartbeat 1분 이상 stale

- stream 은 살아있지만 (TCP) heartbeat 신호가 없는 경우 = agent process 가 deadlocked / GC pause / OOM 직전입니다.
- **대응:**
  ```bash
  kubectl -n aipaas-system top pod -l app=cluster-agent      # CPU / mem 폭주 확인
  kubectl -n aipaas-system describe pod <name> | grep -A5 Events
  kubectl -n aipaas-system logs <name> --previous --tail=200  # 직전 crash 로그
  ```
- mem 폭주면 agent deployment resources.limits.memory 상향 + restart 합니다.
- CPU 폭주면 dispatcher 가 heavy request loop 입니다 — backend 측 호출 패턴을 확인합니다.

## 3. AnycloudAgentStatusFailedOrRevoked — token 인증 실패

- `status=FAILED`: bootstrap 단계 실패입니다 (token 만료 / hash mismatch).
- `status=REVOKED`: 운영자 또는 자동 cleanup 이 `revoked_at` 을 채운 경우입니다 — agent 의 identity_token 이 거부됩니다.

### 복구 흐름

1. cluster 의 새 registration token 발급:
   ```
   POST /v1/clusters/{name}/agent-registration
   ```
   응답에 `registrationToken` + `helmInstall` 명령이 포함됩니다.
2. cluster 안에서 chart 재설치:
   ```bash
   helm upgrade --install cluster-agent oci://... \
     --set bootstrap.registrationToken=<NEW_TOKEN> \
     -n aipaas-system
   ```
3. 또는 raw manifest 를 사용합니다 (`apps/agent/deploy/k8s/agent.yaml`) — token env 만 교체합니다.

## 4. AnycloudFleetDegraded — >20% unhealthy

cluster 개별 문제가 아닌 backend / network 광역 incident 입니다. 점검:

1. **anycloud backend pod 상태** (k8s 환경) — readiness probe 통과 중인지 확인합니다.
2. **gRPC ingress** (cluster agent 가 접속하는 public endpoint) — DNS resolve, TLS handshake 를 확인합니다.
3. **DB latency** — heartbeat 처리 지연이 backend 측 timeout 으로 누적되는지 확인합니다.
4. **scheduled scan**: backend 의 `AgentHealthMetricsBinder.scan()` 이 30s 마다 도는지 (log 확인) 확인합니다.

## 5. AnycloudAgentStreamDown — DB ACTIVE 인데 stream 비활성

- backend restart 직후 5분 안에 자동 복구가 가능합니다 (agent 의 reconnect cycle).
- 5분 이상 지속되면:
  ```bash
  kubectl -n aipaas-system logs <agent-pod> | grep -iE "tls|handshake|reconnect"
  ```
  - TLS handshake error 면 cert renewed without restart 가능성이 있습니다.
  - reconnect attempt 가 없으면 agent 자체 결함입니다 (restart).

## Metric 정의

| Metric | 의미 |
|---|---|
| `anycloud_agent_healthy{cluster}` | 1 = healthy (ACTIVE + stream + fresh heartbeat), 0 = otherwise |
| `anycloud_agent_stream_active{cluster}` | 1 = backend ↔ agent gRPC stream 활성 |
| `anycloud_agent_heartbeat_age_seconds{cluster}` | 마지막 heartbeat 이후 경과 초 (−1 = 신호 없음) |
| `anycloud_agent_status{cluster, status}` | 현재 status (ACTIVE / REGISTERED / DEGRADED / FAILED / REVOKED / NONE) — current=1, others 미존재 |

코드 위치: `apps/anycloud/src/main/java/com/aipaas/anycloud/domain/agent/metrics/AgentHealthMetricsBinder.java` 입니다.

## 관련

- alert 규칙: [`agent-health-alerts.yml`](agent-health-alerts.yml)
- Grafana dashboard: [`agent-health-dashboard.json`](agent-health-dashboard.json)
