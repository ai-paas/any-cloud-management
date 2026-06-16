# Frontend 통합 가이드

anycloud 의 cluster 관리 / 모니터링 / 터미널 / kubeconfig 기능을 frontend 에서 사용하는 방법을 설명합니다.
React / Vue / Svelte 모두 동일한 패턴을 따릅니다 (HTTP + WebSocket).

> **본 문서의 가정**
> - Backend base URL 은 `https://anycloud.example.com` 입니다 (gateway 뒤).
> - 인증은 gateway 가 JWT bearer 를 처리합니다 → backend 는 cluster 권한만 확인합니다.
> - Frontend 가 자체 SPA 입니다 — 별도 BFF 없이 직접 anycloud REST/WebSocket 을 호출합니다.

---

## 목차

1. [Cluster 목록 + 상태](#1-cluster-목록--상태)
2. [Pod Exec 터미널 (xterm.js)](#2-pod-exec-터미널-xtermjs)
3. [Node Debug Shell](#3-node-debug-shell)
4. [Kubeconfig 다운로드](#4-kubeconfig-다운로드)
5. [모니터링 — PromQL 카드](#5-모니터링--promql-카드)
6. [Grafana iframe 임베드](#6-grafana-iframe-임베드)
7. [Alert 토스트 / 인디케이터](#7-alert-토스트--인디케이터)
8. [GPU 토글 (운영자 override)](#8-gpu-토글-운영자-override)
9. [에러 처리 표준](#9-에러-처리-표준)
10. [K8s Resource Explorer — kind picker](#10-k8s-resource-explorer--kind-picker)
11. [Cluster Addon — async install](#11-cluster-addon--async-install)
12. [사용자 RBAC — Impersonation 헤더](#12-사용자-rbac--impersonation-헤더)

---

## 1. Cluster 목록 + 상태

```ts
// list
const res = await fetch("/v1/clusters?source=registered&status=READY");
const { data: clusters } = await res.json();

// 단일 — health 종합 응답
const health = await fetch(`/v1/clusters/${name}/health`).then((r) => r.json());
// health.data: { healthy, summary, agentStatus, streamActive, lastSeenSecondsAgo, hasGpuNodes? }
```

UI 권장 — `healthy=true` 이면 초록 점, `streamActive=false` 이면 노랑 (DB 는 ACTIVE 인데 stream 이 끊긴 상태), `summary` 를 hover tooltip 으로 표시합니다.

```tsx
function ClusterBadge({ name }: { name: string }) {
  const { data } = useSWR(`/v1/clusters/${name}/health`, fetcher, {
    refreshInterval: 15_000, // 15s
  });
  const h = data?.data;
  if (!h) return <Spinner />;
  return (
    <Tooltip content={h.summary}>
      <Dot color={h.healthy ? "green" : h.streamActive ? "yellow" : "red"} />
    </Tooltip>
  );
}
```

---

## 2. Pod Exec 터미널 (xterm.js)

WebSocket 프로토콜 (cluster-agent starter `PodExec`) 은 다음과 같습니다.

- **Client → Server**:
  - Binary frame = stdin bytes
  - Text frame `{"type":"resize","cols":N,"rows":N}` = PTY resize
  - Text frame `{"type":"stdin","data":"<base64>"}` = base64 stdin (binary 미지원 client)
- **Server → Client**:
  - Binary frame = stdout/stderr (TTY 모드에선 머지됨)
  - 마지막 text frame `{"type":"end","exitCode":N,"errorCode":"...","message":"..."}`

```ts
import { Terminal } from "xterm";
import { FitAddon } from "xterm-addon-fit";

function openPodExec(opts: {
  cluster: string;
  namespace: string;
  pod: string;
  container?: string;
  command?: string[];
  el: HTMLDivElement;
}) {
  const term = new Terminal({ convertEol: false, cursorBlink: true });
  const fit = new FitAddon();
  term.loadAddon(fit);
  term.open(opts.el);
  fit.fit();

  const qs = new URLSearchParams({
    tty: "true",
    cols: String(term.cols),
    rows: String(term.rows),
  });
  if (opts.container) qs.set("container", opts.container);
  if (opts.command?.length) qs.set("command", opts.command.join(","));

  const proto = location.protocol === "https:" ? "wss:" : "ws:";
  const ws = new WebSocket(
    `${proto}//${location.host}/v1/clusters/${opts.cluster}` +
      `/pods/${opts.namespace}/${opts.pod}/exec?${qs}`,
  );
  ws.binaryType = "arraybuffer";

  // Server → Terminal
  ws.onmessage = (ev) => {
    if (ev.data instanceof ArrayBuffer) {
      term.write(new Uint8Array(ev.data));
      return;
    }
    // text frame — JSON
    try {
      const msg = JSON.parse(ev.data);
      if (msg.type === "end") {
        term.writeln(`\r\n\x1b[33m[exited ${msg.exitCode}] ${msg.message ?? ""}\x1b[0m`);
      }
    } catch {
      term.write(ev.data);
    }
  };
  ws.onclose = () => term.writeln("\r\n\x1b[31m[connection closed]\x1b[0m");

  // Terminal → Server: stdin
  const enc = new TextEncoder();
  term.onData((d) => {
    if (ws.readyState === WebSocket.OPEN) ws.send(enc.encode(d));
  });

  // Resize → server
  const ro = new ResizeObserver(() => {
    fit.fit();
    if (ws.readyState === WebSocket.OPEN) {
      ws.send(JSON.stringify({ type: "resize", cols: term.cols, rows: term.rows }));
    }
  });
  ro.observe(opts.el);

  return () => {
    ro.disconnect();
    ws.close();
    term.dispose();
  };
}
```

**참고**:
- `tty=true` 가 default 입니다 — 인터랙티브 shell 용입니다. `false` 이면 stdout/stderr 가 분리됩니다.
- `command` 는 comma-separated (`/bin/bash,-l`) 입니다. default 는 `/bin/sh` 입니다.
- WebSocket buffer 는 backend 가 64KB 입니다 — 더 큰 출력은 chunk 됩니다.

---

## 3. Node Debug Shell

2-step 으로 구성됩니다.

```ts
async function openNodeShell(cluster: string, node: string, el: HTMLDivElement) {
  // 1) Debug pod 생성 (host root shell — privileged)
  const res = await fetch(
    `/v1/clusters/${cluster}/nodes/${node}/debug-pod`,
    { method: "POST" },
  );
  if (!res.ok) {
    throw new Error(await res.text());
  }
  const { data } = await res.json();
  // data: { namespace, podName, expiresAt, nodeName }

  // 2) 기존 PodExec WebSocket 재사용
  return openPodExec({
    cluster,
    namespace: data.namespace,
    pod: data.podName,
    command: ["bash"],     // nsenter 가 이미 entrypoint
    el,
  });
}
```

운영자에게 노출 시 **경고가 필수입니다** — host root + 모든 노드 namespace 접근이 가능합니다. RBAC + UI confirm modal 을 권장합니다.

---

## 4. Kubeconfig 다운로드

```ts
async function downloadKubeconfig(cluster: string, opts: {
  namespace: string;
  serviceAccount: string;
  ttlSeconds?: number;
}) {
  const url = `/v1/clusters/${cluster}/kubeconfig?format=yaml`;
  const res = await fetch(url, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify(opts),
  });
  if (!res.ok) {
    throw new Error(`Kubeconfig export failed: ${res.status}`);
  }
  const blob = await res.blob();
  // Disposition: attachment; filename=...
  const link = document.createElement("a");
  link.href = URL.createObjectURL(blob);
  // 서버 헤더 그대로 사용하거나 명시:
  link.download = `${cluster}-kubeconfig.yaml`;
  link.click();
  URL.revokeObjectURL(link.href);
}
```

JSON 메타데이터만 받으려면 `?format=json` 또는 생략합니다. `data.kubeconfigYaml` 에 본문 + `expiresAt` 이 포함됩니다.

**UI 권장 — TTL 슬라이더** (1h / 6h / 24h) + 선택된 ServiceAccount 의 권한 표시입니다 (별도 API 가 필요한 경우 K8s `kubectl auth can-i` 결과를 사용합니다).

---

## 5. 모니터링 — PromQL 카드

```ts
async function queryMetric(cluster: string, promql: string, signal?: AbortSignal) {
  const u = `/v1/clusters/${cluster}/metrics/query?promql=${encodeURIComponent(promql)}`;
  const res = await fetch(u, { signal });
  const { data } = await res.json();
  // data.raw 가 Prometheus JSON 문자열 — 그대로 파싱
  return JSON.parse(data.raw);
}
```

응답은 Prometheus 표준 — `{ status, data: { resultType, result: [...] } }` 입니다. `resultType` 에 따라 시각화합니다.

- `vector` — 단일 값 카드입니다 (예: `up == 1`).
- `matrix` — 시계열 차트입니다 (Recharts / uPlot).

```tsx
function CpuUsageCard({ cluster }: { cluster: string }) {
  const { data } = useSWR(
    `/v1/clusters/${cluster}/metrics/query?promql=` +
      encodeURIComponent("sum(rate(container_cpu_usage_seconds_total[5m]))"),
    fetcher,
    { refreshInterval: 30_000 },
  );
  const result = data?.data.raw && JSON.parse(data.data.raw);
  const value = result?.data.result?.[0]?.value?.[1];
  return <Metric label="CPU" value={value ? Number(value).toFixed(2) : "—"} />;
}
```

**Multi-cluster aggregate**:

```ts
// 모든 ACTIVE cluster 의 메트릭 한 번에 → Map<cluster, raw>
const all = await fetch(
  `/v1/observability/aggregate?promql=${encodeURIComponent("up")}`,
).then((r) => r.json());
// all.data: { "demo-aws-01": { raw: "..." }, "demo-gcp-02": { ... } }
```

---

## 6. Grafana iframe 임베드

```ts
async function getDashboardUrl(cluster: string): Promise<string | null> {
  const res = await fetch(`/v1/clusters/${cluster}/observability/dashboard`);
  if (res.status === 404) return null; // GRAFANA_NOT_EXPOSED
  if (!res.ok) throw new Error("dashboard lookup failed");
  const { data } = await res.json();
  return data.url;
}
```

```tsx
function GrafanaPanel({ cluster, dashboardUid }: { cluster: string; dashboardUid: string }) {
  const { data: url } = useSWR(`/v1/clusters/${cluster}/observability/dashboard`, fetcher);
  if (!url) return <InstallPrompt cluster={cluster} />;
  return (
    <iframe
      src={`${url}/d/${dashboardUid}?kiosk&theme=dark&refresh=30s`}
      style={{ width: "100%", height: 720, border: "none" }}
      sandbox="allow-scripts allow-same-origin"
    />
  );
}
```

**주의** — 운영 환경에서는 Grafana 측 `allow_embedding` + `cookie_samesite: none` 설정이 필요합니다. 권장 방법은 backend 가 Grafana 앞에서 reverse-proxy 하여 동일 origin 으로 통일하는 것입니다 (별도 작업).

**Install fallback** — Grafana 미설치 시 addon async workflow 로 호출합니다. §11 을 참고합니다.

```tsx
function InstallPrompt({ cluster }: { cluster: string }) {
  return (
    <Button onClick={async () => {
      const r = await fetch(`/v1/clusters/${cluster}/addons`, {
        method: "POST",
        headers: {"Content-Type": "application/json"},
        body: JSON.stringify({ type: "MONITORING", catalogId: "kube-prometheus-stack" }),
      });
      const { data: addon } = await r.json();
      // SSE 로 진행 표시 — addon.id 기준
      alert(`Monitoring stack 설치 enqueue: ${addon.id}`);
    }}>
      Install kube-prometheus-stack
    </Button>
  );
}
```

또는 — agent ACTIVE 시 auto-installer 가 자동으로 설치합니다 → 사용자가 명시 install 호출이 불필요합니다.

---

## 7. Alert 토스트 / 인디케이터

```ts
const alerts = await fetch(`/v1/clusters/${cluster}/observability/alerts`)
  .then((r) => r.json());
const list = JSON.parse(alerts.data.raw);     // Alertmanager v2 array
// list: [{ labels: { alertname, severity, ... }, status: { state: "active" }, startsAt }]
```

UI 권장 사항은 다음과 같습니다.
- Header bell 아이콘 + count badge (active alerts) 입니다.
- Click → 측면 drawer 로 list 를 표시합니다.
- severity 별 색상 (critical=red, warning=orange, info=blue) 을 적용합니다.
- `kubectl describe` 없이 backend 가 reachable 한 cluster 의 alert 만 표시합니다.

---

## 8. GPU 토글 (운영자 override)

Sync override endpoint 는 다음과 같습니다.

```tsx
function GpuToggle({ cluster, initial }: { cluster: string; initial: boolean }) {
  const [on, setOn] = useState(initial);
  const update = async (next: boolean) => {
    setOn(next);     // 낙관적 UI
    const res = await fetch(`/v1/clusters/${cluster}/capabilities`, {
      method: "PATCH",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ hasGpuNodes: next }),
    });
    if (!res.ok) {
      setOn(!next);     // rollback
      alert("toggle failed");
    }
  };
  return <Switch checked={on} onChange={update} label="GPU 노드 포함" />;
}
```

Agent 가 자동 backfill 하기 전에 운영자가 미리 설정할 수 있습니다. 다음 cluster ACTIVE 시 dcgm-exporter 가 자동 설치됩니다.

---

## 9. 에러 처리 표준

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

## 10. K8s Resource Explorer — kind picker

cluster 의 K8s 자원을 generic UI 로 탐색합니다. backend 가 agent 의 discovery 결과를 Caffeine cache 로
정규화하므로 (`docs/architecture/kind-resolver.md`) — frontend 는 두 단계만 알면 됩니다.

```ts
// 1) cluster 의 사용 가능 kind 전체 enumerate (395 kinds + CRD 자동 포함)
const kinds = await fetch(`/v1/clusters/${c}/resource-kinds`).then(r => r.json());
// kinds.data: [{plural, kind, namespaced, group, version, shortNames}, ...]

// 2) 사용자가 kind 선택 → namespaced 여부로 분기
if (selected.namespaced) {
  // namespace 목록 fetch → 사용자가 선택
  const ns = await pickNamespace();
  return list(`/v1/clusters/${c}/namespaces/${ns}/${selected.plural}?pageSize=50`);
} else {
  // cluster-scoped → `-` marker 고정 (kubectl 컨벤션)
  return list(`/v1/clusters/${c}/namespaces/-/${selected.plural}?pageSize=50`);
}
```

**Path 컨벤션** (RESTful 유지) 은 다음과 같습니다.
- `namespaces/<ns>` — 특정 namespace 입니다.
- `namespaces/_all` — all-namespaces 입니다 (선택).
- `namespaces/-` — cluster-scoped resource 입니다 (필수).

backend 가 입력을 normalize 합니다 — `_all` / `-` / cluster-scoped kind 모두 알아서 처리합니다.

**Type-ahead UX**: 잘못된 kind 입력 시 backend 가 `UnsupportedKindException` (404 + `metadata.suggestions` Levenshtein top-3) 을 반환합니다. typeahead 입력에 표시합니다.

---

## 11. Cluster Addon — async install

모든 addon (monitoring / velero / gpu) 은 RabbitMQ 비동기 workflow + SSE 진행 상황 push 로 동작합니다.
자세한 사항은 [`../operations/monitoring-usage.md`](../operations/monitoring-usage.md) 를 참고합니다.

```ts
// 1) install 요청 — 즉시 202 + addon row 생성 (state=PENDING/ENQUEUED)
const r = await fetch(`/v1/clusters/${c}/addons`, {
  method: "POST",
  headers: {"Content-Type": "application/json"},
  body: JSON.stringify({
    type: "MONITORING",
    catalogId: "kube-prometheus-stack",
    namespace: "monitoring",
    valuesYaml: "...optional override...",
  }),
});
const { data: addon } = await r.json();
// addon.id = "addon-<uuid>", addon.state = "PENDING" / "ENQUEUED"

// 2) state 진행 — 두 가지 채널
//   (a) polling: GET /v1/clusters/{c}/addons/{addon.id}
//   (b) SSE: GET /v1/clusters/{c}/addons/{addon.id}/events (recommended)
const evt = new EventSource(`/v1/clusters/${c}/addons/${addon.id}/events`);
evt.addEventListener("state-changed", (e) => {
  const next = JSON.parse(e.data); // {state: "INSTALLING"|"SUCCEEDED"|"FAILED", lastError?: string}
  if (next.state === "SUCCEEDED" || next.state === "FAILED") evt.close();
  updateUI(next);
});
```

**상태 머신**: `PENDING → ENQUEUED → INSTALLING → SUCCEEDED | FAILED → DELETING → DELETED` 입니다. `FAILED` 시 `lastError` 를 표시하고 retry 버튼 (`POST .../addons/{id}/retry`) 을 제공합니다.

**GPU 자동 동반 설치**: `MONITORING` install + cluster 의 `hasGpuNodes=true` 시 백엔드가 자동으로 `GPU_EXPORTER` addon row 도 생성합니다. UI 는 별도 호출이 불필요합니다.

---

## 12. 사용자 RBAC — Impersonation 헤더

`security.auth.enabled=true` 환경에서 backend 가 K8s Impersonation pass-through 로 사용자별 RBAC 를 평가합니다
(`docs/architecture/k8s-impersonation-auth.md`). **Frontend 측 변경은 보통 없습니다** — gateway 가 OIDC
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

---

## 부록 — 전체 endpoint 빠른 참조

| Endpoint | 페이지 |
|---|---|
| `GET /v1/clusters` | Cluster 목록 |
| `GET /v1/clusters/{c}` | Cluster 상세 |
| `GET /v1/clusters/{c}/health` | 종합 health |
| `POST /v1/clusters` | 등록/생성 |
| `PATCH /v1/clusters/{c}` | scale/upgrade (비동기) |
| `PATCH /v1/clusters/{c}/capabilities` | GPU flag sync |
| `DELETE /v1/clusters/{c}` | 삭제 |
| `WS /v1/clusters/{c}/pods/{ns}/{pod}/exec` | Pod terminal |
| `POST /v1/clusters/{c}/nodes/{node}/debug-pod` | Node debug pod 생성 |
| `GET /v1/clusters/{c}/kubeconfig` | kubeconfig 다운로드 (YAML attachment, `?serviceAccount=&namespace=&ttlSeconds=`) |
| `POST /v1/clusters/{c}/addons` | addon async install (monitoring/velero/gpu) |
| `GET /v1/clusters/{c}/addons/{id}/events` | SSE addon 진행 상태 |
| `POST /v1/clusters/{c}/addons/{id}/retry` | FAILED addon 재시도 |
| `GET /v1/clusters/{c}/resource-kinds` | kind 목록 (395+ namespaced/cluster-scoped 메타) |
| `GET /v1/clusters/{c}/namespaces/{ns}/{kind}` | 동적 resource list (`-` = cluster-scoped) |
| `GET /v1/clusters/{c}/metrics/query` | PromQL instant |
| `GET /v1/clusters/{c}/metrics/query_range` | PromQL range |
| `GET /v1/observability/aggregate` | Multi-cluster fan-out |
| `GET /v1/clusters/{c}/observability/targets` | Prometheus targets |
| `GET /v1/clusters/{c}/observability/alerts` | Alertmanager alerts |
| `GET /v1/clusters/{c}/observability/dashboard` | Grafana URL |
| `POST /v1/admin/clusters/{c}/kind-cache/flush` | admin — kind cache flush |
