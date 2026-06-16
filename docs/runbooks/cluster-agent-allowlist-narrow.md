# Cluster-agent allowlist — tier 기반 narrow override 가이드

prod cluster 의 agent allowlist 를 좁혀 보안 강화하는 운영 가이드. anycloud backend 의 default
는 *개발 단계 친화 (allow-all)* — 운영 cluster 는 명시적으로 좁혀 사용.

## 배경

cluster-agent 의 `aipaas-agent-allowlist` ConfigMap 이 명령/차트/namespace 의 허용 set 을 정의.
default (chart deploy 와 동시 설치) 는:

```yaml
allowed_commands: [LIST_PODS, GET_LOG, INSTALL_ADDON, APPLY_MANIFEST, EXEC_POD, ...]
allowed_charts: ["*/*:0.0.0-99.99.99"]
allowed_namespaces: ["*"]
```

→ 광범위. 운영 cluster 에선 운영자가 명시적으로 좁혀야 함.

## tier 별 권장 allowlist

### prod cluster

운영 cluster 는 **read-only + 명시 차트 limit**:

```yaml
# kubectl edit configmap aipaas-agent-allowlist -n aipaas-system
data:
  allowed_commands: |
    - "LIST_PODS"
    - "GET_LOG"
    - "GET_RESOURCE"
    - "LIST_RESOURCES"
    - "LIST_RESOURCE_KINDS"
    - "RESOLVE_RESOURCE"
    - "GET_HELM_RELEASE_STATUS"
    - "LIST_HELM_RELEASES"
    - "LIST_HELM_RELEASE_RESOURCES"
    - "LIST_HELM_RELEASE_HISTORY"
    - "QUERY_METRICS"
    - "LIST_METRIC_TARGETS"
    - "LIST_ALERTS"
    - "GET_DASHBOARD_URL"
    - "GET_AGENT_CONFIG"
    - "GET_AGENT_HEALTH"
    - "BACKUP_ETCD"      # backup 운영 명령 — 필요시
    - "BACKUP_PKI"
  allowed_charts: |
    - "prometheus-community/kube-prometheus-stack:60.0.0-65.0.0"
    - "vmware-tanzu/velero:8.0.0-9.0.0"
    - "cilium/cilium:1.15.0-1.16.0"
    # 운영에서 사용하는 차트 + version 명시
  allowed_namespaces: |
    - "monitoring"
    - "velero"
    - "kube-system"
    # 시스템 namespace 만
```

→ APPLY_MANIFEST / EXEC_POD / DELETE_RESOURCE / INSTALL_ADDON 모두 거부. 운영자가 변경 필요 시
명시적 expansion (slack approval + audit trail).

### staging cluster

mid-restrict — 변경 가능하나 차트 정확히 pin:

```yaml
allowed_commands: |
  - "LIST_PODS"
  - "GET_LOG"
  - "EXEC_POD"            # 디버깅 가능
  - "INSTALL_ADDON"       # 사용자가 install
  - "UPGRADE_ADDON"
  - "UNINSTALL_ADDON"
  - "APPLY_MANIFEST"
  # ... (prod 의 read-only 명령 모두 포함)
allowed_charts: |
  - "*/*:0.0.0-99.99.99"   # 모든 차트 (dev 와 동일)
allowed_namespaces: |
  - "*"                    # 모든 namespace
```

### dev cluster

default 유지 (allow-all). 개발자 자유 실험.

## 운영 절차

### 새 cluster 가 prod tier 라면 — 등록 직후 narrow

```bash
# 1. cluster 등록 (anycloud UI 또는 admin API)
POST /v1/clusters  { ..., source: vm, ... }

# 2. cluster ACTIVE 전환 확인 (cluster-agent 정상 부팅)
GET /v1/admin/clusters/{id}/agent/health

# 3. allowlist 좁히기 — anycloud admin API 또는 kubectl
PUT /v1/admin/clusters/{id}/agent-policy
{
  "allowedCommands": ["LIST_PODS", "GET_LOG", ...],
  "allowedCharts": ["prometheus-community/kube-prometheus-stack:60-65"],
  "allowedNamespaces": ["monitoring", "velero", "kube-system"]
}
```

→ backend 의 `APPLY_AGENT_CONFIG` RPC 가 ConfigMap 갱신 → agent 의 watcher 가 즉시 reload.

### 정책 변경 후 검증

```bash
# 1. backend 의 audit 조회
GET /v1/admin/agent/policy/audit
→ cluster 의 severity = INFO/NONE 확인

# 2. agent 측 ConfigMap 확인
kubectl --kubeconfig=... get cm aipaas-agent-allowlist -n aipaas-system -o yaml

# 3. 차트 install attempt 가 거부되는지 (negative test)
POST /v1/clusters/{id}/addons  { catalogId: "monitoring", ... }
→ 본 차트는 allowlist 에 있으면 OK, 없으면 403 from agent
```

### emergency widen

prod incident 중 임시 widen 필요하면:

```bash
PUT /v1/admin/clusters/{id}/agent-policy
{ "allowedCommands": ["*"], ... }
```

→ Slack alert 권장 (운영자가 widen 사실 인지). incident 종료 후 다시 narrow.

## 자동화 (미구현)

`ClusterEntity.tags` 컬럼은 이미 schema 에 존재 (JSON `Map<String, String>`). `tags.tier=prod`
같은 label 기반 자동 narrow 정책은 별 sprint 항목 — 현재는 운영자가 수동 narrow.

## 참조

- [`api-inventory.md`](../architecture/api-inventory.md) § 2.8 — Agent Policy API
- [`AgentPolicyAuditService`](../../apps/anycloud/src/main/java/com/aipaas/anycloud/domain/agent/policy/AgentPolicyAuditService.java) — fleet 의 severity 집계
