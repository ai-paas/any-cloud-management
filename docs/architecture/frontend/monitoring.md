# Frontend 통합 — 모니터링 / Alert / GPU / Addon

Prometheus 메트릭 카드 / Grafana iframe / Alert 토스트 / GPU 토글 / addon async install
통합. 공통 가정은 [`../frontend-integration.md`](../frontend-integration.md) 참고.

## 1. 모니터링 — PromQL 카드

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

## 2. Grafana iframe 임베드

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

**Install fallback** — Grafana 미설치 시 addon async workflow 로 호출합니다. §4 (Cluster Addon) 참고.

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

## 3. Alert 토스트 / 인디케이터

```ts
const alerts = await fetch(`/v1/clusters/${cluster}/observability/alerts`)
  .then((r) => r.json());
const list = JSON.parse(alerts.data.raw);     // Alertmanager v2 array
// list: [{ labels: { alertname, severity, ... }, status: { state: "active" }, startsAt }]
```

UI 권장 사항은 다음과 같습니다.
- Header bell 아이콘 + count badge (active alerts)
- Click → 측면 drawer 로 list 를 표시합니다.
- severity 별 색상 (critical=red, warning=orange, info=blue) 을 적용합니다.
- `kubectl describe` 없이 backend 가 reachable 한 cluster 의 alert 만 표시합니다.

---

## 4. GPU 토글 (운영자 override)

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

## 5. Cluster Addon — async install

모든 addon (monitoring / velero / gpu) 은 RabbitMQ 비동기 workflow + SSE 진행 상황 push 로 동작합니다.
자세한 사항은 [`../../operations/monitoring-usage.md`](../../operations/monitoring-usage.md) 를 참고합니다.

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
