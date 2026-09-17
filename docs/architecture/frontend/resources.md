# Frontend 통합 — Cluster 자원 / Kubeconfig / Resource Explorer

cluster 자원 조회 / kubeconfig 발급 / 동적 K8s 자원 탐색 페이지에서 사용하는 API 패턴.
공통 가정은 [`../frontend-integration.md`](../frontend-integration.md) 참고.

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

## 2. Kubeconfig 다운로드

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

## 3. K8s Resource Explorer — kind picker

cluster 의 K8s 자원을 generic UI 로 탐색합니다. backend 가 agent 의 discovery 결과를 Caffeine cache 로
정규화하므로 (`../kind-resolver.md`) — frontend 는 두 단계만 알면 됩니다.

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
- `namespaces/<ns>` — 특정 namespace
- `namespaces/_all` — all-namespaces 입니다 (선택).
- `namespaces/-` — cluster-scoped resource 입니다 (필수).

backend 가 입력을 normalize 합니다 — `_all` / `-` / cluster-scoped kind 모두 알아서 처리합니다.

**Type-ahead UX**: 잘못된 kind 입력 시 backend 가 `UnsupportedKindException` (404 + `metadata.suggestions` Levenshtein top-3) 을 반환합니다. typeahead 입력에 표시합니다.
