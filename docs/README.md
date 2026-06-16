# Documentation Index

`any-cloud-management` 의 모든 문서를 카테고리로 정리합니다. 운영자·개발자·신규 합류자가 필요한
영역을 빠르게 찾을 수 있게 합니다.

> **현재 상태 reference** = 본 docs/ 아래 카테고리 폴더 <br>
> **변경 이력** = `git log` (또는 PR 본문)

## 신규 합류자 — 첫 30분 코스

본 프로젝트는 **multi-CSP K8s 프로비저닝 + 운영 백엔드** 입니다. cluster-agent 가 K8s cluster
안에서 운영을 수행하고, anycloud backend 가 reverse-tunnel gRPC 로 fleet 을 관리합니다.

1. [`operations/quickstart.md`](./operations/quickstart.md) — local dev stack 5분 setup (Bruno + Swagger)
2. [`architecture/overview.md`](./architecture/overview.md) — monorepo 의 component / package / 의존성 지도
3. [`architecture/feature-flows.md`](./architecture/feature-flows.md) — cluster 등록 / 갱신 / 회수 end-to-end
4. [`conventions/folder-structure.md`](./conventions/folder-structure.md) — 코드 둘러볼 때 길잡이 (feature-first `domain/{X}/`)
5. (선택) [`architecture/cluster-agent.md`](./architecture/cluster-agent.md) — reverse-tunnel topology + 인증 model

코드 setup + 컨벤션: 본 doc + [`docs/conventions/`](./conventions/) (도메인별 컨벤션).

## architecture/ — 시스템 설계

거시 구성 + 컴포넌트 경계 + 데이터 흐름입니다.

### Core

- [overview.md](./architecture/overview.md) — monorepo 의 component / package / 의존성 지도
- [api-inventory.md](./architecture/api-inventory.md) — REST + gRPC endpoint 전체 목록
- [feature-flows.md](./architecture/feature-flows.md) — 등록 / 갱신 / 회수 / 업그레이드 end-to-end flow
- [k8s-access-paths.md](./architecture/k8s-access-paths.md) — backend ↔ K8s 접근 경로
- [vmcluster-state-machine.md](./architecture/vmcluster-state-machine.md) — VM cluster lifecycle state machine
- [vmcluster-workflow.md](./architecture/vmcluster-workflow.md) — VM cluster RabbitMQ workflow (provision → bootstrap → verify → ready)
- [frontend-integration.md](./architecture/frontend-integration.md) — frontend ↔ backend 통합 (`/v1` API + SSE + webhook)
- [dependency-rationalization.md](./architecture/dependency-rationalization.md) — 의존성 결정 (MariaDB + RabbitMQ stack)

### Cluster Agent

- [cluster-agent.md](./architecture/cluster-agent.md) — cluster-agent 아키텍처
- [helm-repo-sync.md](./architecture/helm-repo-sync.md) — backend ↔ agent helm-repo sync 모델
- [k8s-impersonation-auth.md](./architecture/k8s-impersonation-auth.md) — K8s Impersonation pass-through 인증
- [dynamic-addon-rbac.md](./architecture/dynamic-addon-rbac.md) — addon 설치 시 catalog 기반 ClusterRoleBinding 자동 적용
- [monitoring-discovery.md](./architecture/monitoring-discovery.md) — agent prometheus service auto-discovery
- [kind-resolver.md](./architecture/kind-resolver.md) — kind metadata schema cache (Caffeine TTL 30분)

### Pulumi / Infra ([pulumi/](./architecture/pulumi/))

- [pulumi-multicloud-k8s-blueprint.md](./architecture/pulumi/pulumi-multicloud-k8s-blueprint.md) — 멀티 CSP VM Kubernetes 프로비저닝 청사진
- [pulumi-runtime-with-gateway.md](./architecture/pulumi/pulumi-runtime-with-gateway.md) — Gateway + Spring + Pulumi 운영 구조 / RustFS + OpenBao
- [pulumi-gpu-support.md](./architecture/pulumi/pulumi-gpu-support.md) — Pulumi GPU node + DCGM exporter

### Starter Modules ([starters/](./architecture/starters/))

- [cluster-agent-starter.md](./architecture/starters/cluster-agent-starter.md) — **Layer 1** — Reverse-tunnel gRPC + PodExec WebSocket + Kube/Helm 서비스
- [cluster-agent-features-starter.md](./architecture/starters/cluster-agent-features-starter.md) — **Layer 2 통합** — RBAC + Backup + Observability sub-feature 3개
  - [cluster-agent-rbac-starter.md](./architecture/starters/cluster-agent-rbac-starter.md) — RBAC sub-feature (OIDC → ClusterRoleBinding)
  - [cluster-agent-backup-starter.md](./architecture/starters/cluster-agent-backup-starter.md) — Backup sub-feature (Velero / etcd / PKI)
  - [cluster-agent-observability-starter.md](./architecture/starters/cluster-agent-observability-starter.md) — Observability sub-feature (PromQL + alert + dashboard)
- [cluster-provisioning-starter.md](./architecture/starters/cluster-provisioning-starter.md) — **별도 lifecycle** (cluster-agent 의존 X) — Pulumi CLI 위임 multi-cloud provisioning
- [starter-extension-guide.md](./architecture/starters/starter-extension-guide.md) — 도메인 특화 starter 확장 가이드
- [starter-publishing.md](./architecture/starters/starter-publishing.md) — Maven 게시 절차

### RBAC / Identity

- [oidc-binding-multi-idp.md](./architecture/oidc-binding-multi-idp.md) — OIDC group binding (Keycloak / pocket-id / Google / Entra / generic)

## api/ — 외부 노출 API

HTTP 엔드포인트 명세, 요청·응답 envelope 입니다.

- [v1-reference.md](./api/v1-reference.md) — 현재 v1 API 전체 reference (단일 source)
- [vm-options.md](./api/vm-options.md) — `/v1/providers/*` (provider / region / spec / image)
- [csp-credential.md](./api/csp-credential.md) — `/v1/credentials/*`
- [vm-cluster-preflight.md](./api/vm-cluster-preflight.md) — `/v1/cluster-validations` (생성 전 사전 검증)
- [provider-credential-matrix.md](./api/provider-credential-matrix.md) — 8 CSP 자격증명/필수 config 매트릭스
- [webhooks.md](./api/webhooks.md) — 클러스터 상태 변화 webhook (HMAC 검증 / 멱등 / 재시도)

## conventions/ — 개발 표준

- [comment-style.md](./conventions/comment-style.md) — Javadoc / 주석 스타일
- [dto-naming-convention.md](./conventions/dto-naming-convention.md) — Request / Response / Dto / Entity / Info suffix 표준
- [db-migration-style-guide.md](./conventions/db-migration-style-guide.md) — Flyway migration 작성 규칙
- [ux-terminology-vm-cluster.md](./conventions/ux-terminology-vm-cluster.md) — VM Options / VM Cluster / Registered Cluster 용어 통일

## operations/ — 운영 가이드

- [quickstart.md](./operations/quickstart.md) — dev 5분 부팅
- [db-setup.md](./operations/db-setup.md) — Flyway auto-apply + 호환성
- [day-2-operations.md](./operations/day-2-operations.md) — Scale / Upgrade / Patch / Rollback / DLQ / SLO 10 시나리오
- [runbook-agent-health.md](./operations/runbook-agent-health.md) — health endpoint + alert runbook
- [monitoring-usage.md](./operations/monitoring-usage.md) — addon-기반 monitoring 설치 + raw query endpoint
- [e2e-checklist.md](./operations/e2e-checklist.md) — VM Cluster 생성/삭제 검증 체크리스트
- [test-coverage.md](./operations/test-coverage.md) — Jacoco INSTRUCTION baseline + 측정 계획
- `agent-health-alerts.yml` / `agent-health-dashboard.json` — PrometheusRule + Grafana dashboard import 자료. 운영자가 본인 Prometheus / Grafana 인스턴스에 수동 import 하는 source-of-truth (helm chart 자동 deploy X). 다른 영역 alert rule 은 `libs/cluster-agent-features-spring-boot-starter/src/main/resources/alert-rules/` 참조.

## runbooks/ — 트러블슈팅 / 운영 절차

### Cluster-agent policy / RBAC
- [cluster-agent-resource-policy.md](./runbooks/cluster-agent-resource-policy.md) — agent ConfigMap `resource_policy` 운영 (kind-level 정책 제어)
- [cluster-agent-allowlist-narrow.md](./runbooks/cluster-agent-allowlist-narrow.md) — prod cluster allowlist narrow override (보안 강화)
- [cluster-agent-namespace-wildcard.md](./runbooks/cluster-agent-namespace-wildcard.md) — `allowed_namespaces: ["*"]` 트러블슈팅
- [cluster-agent-configmap-migration.md](./runbooks/cluster-agent-configmap-migration.md) — 기존 chart 의 `helm.sh/resource-policy: keep` migration
- [cluster-agent-secret-cleanup.md](./runbooks/cluster-agent-secret-cleanup.md) — cluster 삭제 후 K8s Secret 정리
- [aggregate-to-view-crd-runbook.md](./runbooks/aggregate-to-view-crd-runbook.md) — impersonation 의 K8s `view` aggregate 안 되는 CRD 운영

### Identity / fallback
- [impersonation-production-activation.md](./runbooks/impersonation-production-activation.md) — Impersonation pass-through 운영 활성화 step-by-step
- [keycloak-outage.md](./runbooks/keycloak-outage.md) — Keycloak / OIDC IdP outage 시 운영자 fallback

### Cluster lifecycle / 도메인
- [gpu-cluster.md](./runbooks/gpu-cluster.md) — NVIDIA GPU cluster 생성 / 운영 / troubleshoot
- [cluster-upgrade.md](./runbooks/cluster-upgrade.md) — K8s minor version upgrade — anycloud 책임 외 (운영 가이드)

## 빠른 진입 가이드

| 역할 | 시작 문서 |
| --- | --- |
| 신규 합류 개발자 | `architecture/overview.md` → `architecture/feature-flows.md` → `architecture/vmcluster-workflow.md` |
| API 통합자 (gateway / FE) | `api/v1-reference.md` → `architecture/frontend-integration.md` |
| 운영자 / SRE | `operations/quickstart.md` → `architecture/pulumi/pulumi-runtime-with-gateway.md` → `operations/day-2-operations.md` → `runbooks/` |
| Provider 확장 (새 CSP) | `architecture/pulumi/pulumi-multicloud-k8s-blueprint.md` §4–5 → `api/provider-credential-matrix.md` |
| 인증 / RBAC | `architecture/k8s-impersonation-auth.md` → `architecture/oidc-binding-multi-idp.md` → `architecture/dynamic-addon-rbac.md` |
| Cluster addon / monitoring | `operations/monitoring-usage.md` → `architecture/kind-resolver.md` |
| 외부 포털 통합 (webhook) | `api/webhooks.md` |

---

새 sprint 마무리할 때 본 README 카테고리를 갱신합니다. 변경 이력은 git log + PR 본문이 source-of-truth.
