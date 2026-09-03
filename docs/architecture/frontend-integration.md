# Frontend 통합 가이드

anycloud 의 cluster 관리 / 모니터링 / 터미널 / kubeconfig 기능을 frontend 에서 사용하는 방법.
React / Vue / Svelte 모두 동일한 패턴 (HTTP + WebSocket).

> **본 문서의 가정**
> - Backend base URL 은 `https://anycloud.example.com` (gateway 뒤).
> - 인증은 gateway 가 JWT bearer 처리 → backend 는 cluster 권한만 확인.
> - Frontend 가 자체 SPA — 별도 BFF 없이 직접 anycloud REST/WebSocket 호출.

## 영역별 가이드

| 영역 | 문서 | 주요 endpoint |
|---|---|---|
| Cluster 자원 / kubeconfig / kind picker | [`frontend/resources.md`](./frontend/resources.md) | `/v1/clusters`, `/v1/clusters/{c}/kubeconfig`, `/v1/clusters/{c}/resource-kinds` |
| Pod Exec / Node Debug Shell (xterm.js) | [`frontend/terminal.md`](./frontend/terminal.md) | `WS /v1/clusters/{c}/pods/{ns}/{pod}/exec`, `POST /v1/clusters/{c}/nodes/{n}/debug-pod` |
| 모니터링 (PromQL / Grafana iframe / Alert / GPU / Addon install) | [`frontend/monitoring.md`](./frontend/monitoring.md) | `/v1/clusters/{c}/metrics/query`, `/v1/clusters/{c}/observability/*`, `/v1/clusters/{c}/addons` |
| 에러 처리 / Impersonation RBAC | [`frontend/auth-and-errors.md`](./frontend/auth-and-errors.md) | 모든 endpoint (envelope 일관) |

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
