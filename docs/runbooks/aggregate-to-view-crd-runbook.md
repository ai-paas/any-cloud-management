# AGGREGATE-TO-VIEW CRD Runbook

impersonation pass-through 의 trade-off 항목 "K8s `view` 미라벨 CRD" 에 대한 운영자 가이드입니다. 어떤 CRD 가 자동 aggregate 안 되는지, 사용자에게 어떻게 binding 을 줘야 하는지를 다룹니다.

## 1. 배경

K8s 의 default `view` / `edit` / `admin` ClusterRole 은 **ClusterRole aggregation rules** 로 다른
ClusterRole 의 권한을 자동 흡수합니다.

```yaml
# kubectl get clusterrole view -o yaml
aggregationRule:
  clusterRoleSelectors:
  - matchLabels:
      rbac.authorization.k8s.io/aggregate-to-view: "true"
```

CRD operator 가 자신의 read 권한 ClusterRole 에 `aggregate-to-view: "true"` 라벨을 붙이면 `view`
역할이 자동으로 그 CRD 도 read 가 가능합니다. impersonation 환경의 사용자는 추가 binding 없이 CRD list/get 이 가능합니다.

**문제**: 다수 third-party operator 가 이 라벨을 안 붙입니다 → `view` 역할 사용자가 그 CRD 를 못 봅니다
→ `degradedReason=FORBIDDEN` 입니다.

## 2. 자주 누락된 operator 카탈로그

### 2.1 확인 명령

```bash
# 모든 CRD 의 group/version 추출
kubectl get crd -o jsonpath='{range .items[*]}{.spec.group}/{.spec.versions[0].name}|{.spec.names.kind}{"\n"}{end}'

# aggregate-to-view 라벨 있는 ClusterRole list
kubectl get clusterrole -l rbac.authorization.k8s.io/aggregate-to-view=true \
  -o jsonpath='{.items[*].metadata.name}'

# 특정 CRD 가 view 에 포함되는지
kubectl auth can-i list <crd-resource> --as=system:serviceaccount:default:test-view-sa
```

### 2.2 자주 누락된 operator 목록 (관찰 기반)

| Operator | 누락된 CRD group | 영향 |
| --- | --- | --- |
| ArgoCD | `argoproj.io` (Application, ApplicationSet, AppProject) | 사용자가 ArgoCD UI 외 kubectl 로 본인 앱 못 봄 |
| Velero | `velero.io` (Backup, Restore, Schedule, BackupStorageLocation) | backup status 확인 불가 — anycloud Velero 페이지가 backend SA 권한으로만 조회 |
| cert-manager (구버전) | `cert-manager.io` (Certificate, Issuer) | TLS 갱신 상태 확인 불가. 신버전은 라벨 부여됨 |
| Prometheus Operator (구) | `monitoring.coreos.com` (Prometheus, PrometheusRule, ServiceMonitor) | 알림룰 / scrape target 확인 불가. 0.66+ 부여됨 |
| Strimzi Kafka | `kafka.strimzi.io` | Kafka cluster status 확인 불가 |
| Knative Serving | `serving.knative.dev` | Knative service list 불가 |
| OpenEBS / Longhorn | `cstor.openebs.io` / `longhorn.io` | PV detail 확인 불가 |
| MetalLB | `metallb.io` (IPAddressPool, L2Advertisement) | LB IP 풀 확인 불가 |

> 본 목록은 관찰 + 환경별로 다릅니다. 실측은 §3 절차로 진행합니다.

## 3. 실측 절차 — 특정 cluster 의 누락 CRD 식별

```bash
# 1) 모든 CRD 의 list verb 가 view ClusterRole 로 가능한지 일괄 점검.
CRDS=$(kubectl get crd -o jsonpath='{.items[*].spec.names.plural}')
for crd in $CRDS; do
  result=$(kubectl auth can-i list "$crd" --as=system:serviceaccount:default:dummy-view 2>&1)
  if [ "$result" != "yes" ]; then
    echo "MISSING $crd"
  fi
done
```

> dummy-view SA 는 `view` ClusterRole binding 만 받은 테스트 계정입니다. 누락된 CRD 가 출력됩니다.

대안 (실제 사용자 권한 — impersonation 활성) 입니다.

```bash
kubectl auth can-i list applications.argoproj.io \
  --as=alice@example.com --as-group=dev-team
```

## 4. 운영 처방 — 3가지 옵션

### 4.1 Option A — operator 의 ClusterRole 에 라벨 부여 (one-shot)

operator 가 출고한 `*-view` ClusterRole 에 patch 합니다.

```bash
kubectl patch clusterrole argo-cd-application-controller-view \
  --type=json -p='[{"op":"add","path":"/metadata/labels/rbac.authorization.k8s.io~1aggregate-to-view","value":"true"}]'
```

장점: 모든 사용자가 즉시 권한을 받습니다. helm chart upgrade 시 덮어쓰지 않게 `kustomize patch` 또는 GitOps 레이어에 영구 반영이 필요합니다.

### 4.2 Option B — 명시 ClusterRoleBinding 작성 (manual)

OidcGroupBinding 미사용 환경 또는 임시 운영 시 사용합니다.

```yaml
apiVersion: rbac.authorization.k8s.io/v1
kind: ClusterRoleBinding
metadata:
  name: dev-team-argocd-view
subjects:
- kind: Group
  name: dev-team
  apiGroup: rbac.authorization.k8s.io
roleRef:
  kind: ClusterRole
  name: argo-cd-application-controller-view
  apiGroup: rbac.authorization.k8s.io
```

장점: cluster 수정이 없습니다. 단점: 사용자 group 별 binding 이 폭증합니다.

### 4.3 Option D — OidcGroupBinding (recommended)

backend REST API (`POST /v1/oidc/bindings`) 로 binding 을 등록합니다 →
backend `OidcBindingExpander` 가 expand 합니다 → `APPLY_AGENT_CONFIG.oidc_bindings` 로 agent push 합니다 →
agent `OidcBindingReconciler` 가 ClusterRoleBinding 을 apply 합니다. 자세한 multi-IdP 설계는
`docs/architecture/oidc-binding-multi-idp.md` 를 참고합니다.

```yaml
# backend REST body (POST /v1/oidc/bindings)
name: dev-team-argocd-view
spec:
  oidcGroupSelector:
    matchExact: [dev-team]
  targetSubjects:
    - kind: Group
      name: $oidcGroup
  roleRefs:
    - kind: ClusterRole
      name: argo-cd-application-controller-view
      scope: ClusterScope
  reconcile:
    pruneOrphans: true
```

backend 가 즉시 (matchExact 는 eager) ClusterRoleBinding 을 apply 합니다. annotation 으로 ownership tracking 합니다
(`aipaas.io/managed-by=cluster-agent` + `aipaas.io/oidc-source-binding`).

**활성화 절차** (별도 chart 옵션이 없습니다. impersonation 활성 + OidcGroupBinding 등록 만으로 동작합니다):
```bash
# impersonation 활성 (cluster-agent helm)
helm upgrade cluster-agent ... --set security.auth.enabled=true
# Binding 등록
curl -X POST .../v1/oidc/bindings -d @dev-team-argocd-view.json
```

## 5. impersonation 활성화 시 사용자 입장 진단 flow

사용자가 anycloud UI 에서 ArgoCD Application 목록을 못 보는 경우 (`FORBIDDEN`) 입니다.

```bash
# 1) anycloud 가 받은 user identity 확인 — backend log
kubectl logs deploy/anycloud | grep ImpersonationInterceptor | tail -5

# 2) 그 identity 로 K8s 가 받아들이는지
kubectl auth can-i list applications.argoproj.io \
  --as=alice@example.com --as-group=dev-team

# 3) "no" 면 §4 의 처방 적용. 예 — Option A:
kubectl patch clusterrole argo-cd-application-controller-view \
  --type=json -p='[{"op":"add","path":"/metadata/labels/rbac.authorization.k8s.io~1aggregate-to-view","value":"true"}]'

# 4) 사용자 즉시 재시도 — anycloud 의 KindResolver Caffeine cache (TTL 30min) 가 stale 일 수 있으므로
#    admin policy refresh 호출:
curl -X POST "$BASE/admin/clusters/<id>/agent-policy/refresh"
```

## 6. 모니터링 — Micrometer metric (제안)

`classifyDegradedCause` 가 `FORBIDDEN` 라벨로 분류한 응답을 cluster + kind 별로 집계합니다.

```
cluster_agent.forbidden.count{cluster="prod-c1", kind="applications.argoproj.io"} 47
cluster_agent.forbidden.count{cluster="prod-c1", kind="backups.velero.io"} 12
```

대시보드에서 임계 초과 시 운영자 알림 → 본 runbook 의 §3 진단을 자동 트리거합니다.

> 현재 metric 은 일반 `cluster_agent.command.failure` 만 있습니다 — kind 별 분류는 없습니다.

## 7. 관련 문서

- `docs/architecture/k8s-impersonation-auth.md` — impersonation pass-through design + trade-off §4.4
- `docs/architecture/oidc-group-k8s-binding-webhook.md` — OidcGroupBinding CRD 도입 시 자동 binding 이 가능합니다.
- `classifyDegradedCause` — FORBIDDEN 라벨 분류 위치입니다.
- KindResolver — Caffeine cache 30min TTL (CRD 추가 후 사용자가 즉시 보려면 refresh 가 필요합니다)
