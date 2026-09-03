# Frontend 통합 — 에러 처리 / Impersonation RBAC

API 에러 envelope 일관 처리 + 사용자별 RBAC pass-through (Impersonation) 가이드.
공통 가정은 [`../frontend-integration.md`](../frontend-integration.md) 참고.

## 1. 에러 처리 표준

모든 API 가 일관된 응답을 반환합니다.

```json
{
  "success": false,
  "status": 503,
  "message": "[NO_ACTIVE_AGENT] no active agent stream for cluster demo-aws-01",
  "data": null
}
```

HTTP status 매핑은 다음과 같습니다.

| Status | 의미 | UX 권장 |
|---|---|---|
| 503 | NO_ACTIVE_AGENT — agent 미연결 | "Cluster 와 통신이 끊겼습니다. agent 상태 확인 필요." + 재시도 버튼 |
| 504 | TIMEOUT — agent 응답 지연 | "잠시 후 다시 시도하세요." auto-retry (1회) |
| 404 | GRAFANA_NOT_EXPOSED / SERVICE_ACCOUNT_NOT_FOUND | 상황별 안내 (설치 prompt / SA 목록) |
| 403 | NAMESPACE_NOT_ALLOWED / CHART_NOT_ALLOWED | 운영자 권한 안내 — AllowList ConfigMap 수정 |
| 400 | MISSING_QUERY / INVALID_PARAMS | form validation 메시지 |
| 502 | AGENT_CALL_FAILED — 그 외 | 일반 오류 + 재시도 |

```ts
async function apiCall<T>(url: string, init?: RequestInit): Promise<T> {
  const res = await fetch(url, init);
  const body = await res.json().catch(() => null);
  if (!res.ok) {
    const msg = body?.message ?? `HTTP ${res.status}`;
    throw new ApiError(msg, res.status, extractCode(body?.message));
  }
  return body.data as T;
}

function extractCode(msg?: string): string | undefined {
  const m = msg?.match(/^\[([A-Z_]+)\]/);
  return m?.[1];
}
```

---

## 2. 사용자 RBAC — Impersonation 헤더

`security.auth.enabled=true` 환경에서 backend 가 K8s Impersonation pass-through 로 사용자별 RBAC 를 평가합니다
(`../identity/k8s-impersonation-auth.md`). **Frontend 측 변경은 보통 없습니다** — gateway 가 OIDC
인증 후 다음 헤더를 backend 로 전파합니다.

```
X-Forwarded-User: alice@example.com
X-Forwarded-Groups: dev-team,ops-team
X-Forwarded-Extra-<key>: <value>  (drrarely)
```

Backend interceptor (`ImpersonationInterceptor`) 가 자동으로 `ThreadLocalImpersonationContext` 에 set 합니다 →
agent gRPC call 에 자동 forward 됩니다 → K8s 가 alice 의 RBAC 으로 평가합니다.

**Frontend 영향**은 다음과 같습니다.
- 사용자가 권한 없는 resource list 호출 시 응답이 `degraded=true, degradedReason="FORBIDDEN"` 으로
  표시됩니다. UI 가 `degradedReason` 별 메시지로 분기합니다.
  - `FORBIDDEN` → "이 자원에 대한 권한이 없습니다 — 관리자에게 ClusterRoleBinding 을 요청합니다."
  - `AGENT_INACTIVE` → "cluster agent 가 응답하지 않습니다."
  - `RESOURCE_KIND_DENIED` → "ConfigMap aipaas-agent-allowlist 의 resource_policy 를 확인합니다."
- toggle OFF 환경 (default) — interceptor 가 미등록 상태이며, 모든 요청이 admin-equivalent 입니다. 응답에 차이가 없습니다.

```ts
function explain(d: { degraded?: boolean; degradedReason?: string; degradedMessage?: string }) {
  if (!d.degraded) return null;
  switch (d.degradedReason) {
    case "FORBIDDEN":
      return "이 자원에 대한 권한이 없습니다. 관리자에게 요청하세요.";
    case "AGENT_INACTIVE":
      return "Cluster agent 가 연결되지 않았습니다. agent 상태를 확인하세요.";
    case "RESOURCE_KIND_DENIED":
      return "Agent 정책이 본 kind 를 거부했습니다 (resource_policy).";
    case "UNSUPPORTED_KIND":
      return d.degradedMessage ?? "지원하지 않는 kind 입니다.";
    case "CIRCUIT_OPEN":
      return "일시적 장애로 차단 — 30초 후 재시도.";
    default:
      return d.degradedMessage ?? "요청 처리 실패.";
  }
}
```
