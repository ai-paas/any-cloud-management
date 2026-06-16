# Impersonation Pass-through Production Activation

운영자용 step-by-step runbook 입니다. 코드 구성요소 (backend interceptor, audit aspect, agent impersonate-aware client, RBAC chart) 는 이미 배포된 image 에 포함되어 있으며, 본 runbook 은 **인프라/운영 환경 설정만** 다룹니다.

## 0. 사전 점검

```bash
# A) backend image 에 impersonation interceptor + audit aspect + degraded metric 포함 확인
kubectl get deploy/anycloud -o jsonpath='{.spec.template.spec.containers[0].image}'

# B) agent image 가 impersonate-aware client + RBAC 포함
kubectl --context=<cluster> -n aipaas-system get deploy/cluster-agent \
  -o jsonpath='{.spec.template.spec.containers[0].image}'

# C) helm chart 가 impersonate verb 포함하는지
helm --kube-context=<cluster> get values cluster-agent -n aipaas-system | grep -A 3 rbac
```

위 3가지 모두 OK 면 4단계 활성화를 시작합니다.

## 1. Gateway 측 X-Forwarded-User 헤더 첨가

OIDC/JWT 검증 후 user identity 를 trusted header 로 backend 에 전달합니다.

### 1.1 nginx-ingress (auth_request 패턴)

```yaml
# nginx.ingress.kubernetes.io/configuration-snippet
auth_request /oauth2/auth;
auth_request_set $user $upstream_http_x_auth_request_user;
auth_request_set $groups $upstream_http_x_auth_request_groups;

proxy_set_header X-Forwarded-User $user;
proxy_set_header X-Forwarded-Groups $groups;
# 외부 client 가 직접 헤더 set 못하게 strip:
proxy_set_header X-Forwarded-Extra-Tenant "";
```

### 1.2 Envoy (External AuthZ filter)

```yaml
http_filters:
- name: envoy.filters.http.ext_authz
  typed_config:
    "@type": type.googleapis.com/envoy.extensions.filters.http.ext_authz.v3.ExtAuthz
    transport_api_version: V3
    http_service:
      server_uri:
        uri: http://oauth2-proxy:4180
      authorization_response:
        allowed_upstream_headers:
          patterns:
          - exact: x-forwarded-user
          - exact: x-forwarded-groups
          - prefix: x-forwarded-extra-
```

### 1.3 Traefik (ForwardAuth middleware)

```yaml
apiVersion: traefik.io/v1alpha1
kind: Middleware
metadata:
  name: oidc-forward-auth
spec:
  forwardAuth:
    address: http://oauth2-proxy:4180/oauth2/auth
    trustForwardHeader: true
    authResponseHeaders:
      - X-Forwarded-User
      - X-Forwarded-Groups
```

### 1.4 검증

```bash
# Gateway 통해 echo endpoint 호출 — 헤더가 전파되는지
curl -i "$BASE/v1/debug/echo-headers" | grep -i 'X-Forwarded-'
# 기대: X-Forwarded-User: alice@example.com / X-Forwarded-Groups: dev-team,ops-team
```

> debug endpoint 가 없다면 `kubectl logs deploy/anycloud | grep ImpersonationInterceptor` 로 backend
> 측 로그를 확인합니다 (DEBUG level 필요 — `logging.level.com.aipaas.anycloud.configuration.security=DEBUG`).

## 2. K8s 측 ClusterRoleBinding 작성

cluster 운영자가 사용자/group 별 권한을 부여합니다. 예시 — `dev-team` 에 `view`, `ops-team` 에 `edit` 입니다.

```yaml
---
apiVersion: rbac.authorization.k8s.io/v1
kind: ClusterRoleBinding
metadata:
  name: oidc-dev-team-view
subjects:
- kind: Group
  name: dev-team
  apiGroup: rbac.authorization.k8s.io
roleRef:
  kind: ClusterRole
  name: view
  apiGroup: rbac.authorization.k8s.io
---
apiVersion: rbac.authorization.k8s.io/v1
kind: ClusterRoleBinding
metadata:
  name: oidc-ops-team-edit
subjects:
- kind: Group
  name: ops-team
  apiGroup: rbac.authorization.k8s.io
roleRef:
  kind: ClusterRole
  name: edit
  apiGroup: rbac.authorization.k8s.io
```

```bash
kubectl --context=<cluster> apply -f bindings.yaml
```

### 2.1 binding 부담이 과한 환경

각 cluster 마다 group binding 을 수동 작성하는 부담이 있으면 → OIDC group 자동 매핑 webhook 도입을 검토합니다.

### 2.2 view 권한이 못 보는 CRD

ArgoCD / Velero / Strimzi 등 — OidcGroupBinding operator 가 배포되어 있으면 CR 로 자동 binding 됩니다 (`aggregate-to-view-crd-runbook.md` §4.3 Option D).

## 3. Agent helm upgrade — impersonate verb 활성화

`aipaas-agent-core` ClusterRole 에 impersonate verb 가 포함된 chart 로 upgrade 합니다.

```bash
helm --kube-context=<cluster> upgrade cluster-agent \
  apps/agent/deploy/helm/cluster-agent \
  -n aipaas-system --reuse-values
```

### 3.1 검증

```bash
# agent SA 가 impersonate 권한 보유
kubectl --context=<cluster> auth can-i impersonate users \
  --as=system:serviceaccount:aipaas-system:aipaas-agent-core
# 기대: yes

kubectl --context=<cluster> auth can-i impersonate groups \
  --as=system:serviceaccount:aipaas-system:aipaas-agent-core
# 기대: yes
```

## 4. Backend 활성화 — security.auth.enabled=true

application.yaml (또는 ConfigMap) 입니다.

```yaml
security:
  auth:
    enabled: true
  kube:
    redact-secrets: true   # default ON
```

```bash
kubectl rollout restart deploy/anycloud
kubectl rollout status deploy/anycloud --timeout=2m
```

### 4.1 검증 (end-to-end)

```bash
# A) viewer 권한 사용자가 secrets list 시 — degraded=true, reason=FORBIDDEN
curl -s -H "X-Forwarded-User: alice@example.com" -H "X-Forwarded-Groups: viewer" \
  "$BASE/v1/clusters/<c>/namespaces/default/secrets?pageSize=10" | \
  jaq '.data | {degraded, degradedReason, returnedItemCount}'
# 기대: {"degraded": true, "degradedReason": "FORBIDDEN", "returnedItemCount": 0}

# B) editor 권한 사용자가 같은 호출 — 정상
curl -s -H "X-Forwarded-User: bob@example.com" -H "X-Forwarded-Groups: ops-team" \
  "$BASE/v1/clusters/<c>/namespaces/default/secrets?pageSize=10" | \
  jaq '.data | {degraded, returnedItemCount}'
# 기대: {"degraded": null, "returnedItemCount": 10}

# C) audit_log 에 principal 기록
mysql -e "SELECT principal, action, status_code, created_at \
  FROM anycloud.audit_log WHERE principal IS NOT NULL ORDER BY created_at DESC LIMIT 5;"
# 기대: alice@example.com / bob@example.com 의 row

# D) Micrometer metric — FORBIDDEN count 증가
curl -s "$BASE/actuator/prometheus" | grep cluster_agent_degraded_count
# 기대: cluster_agent_degraded_count{cluster="<c>",kind="secrets",reason="FORBIDDEN"} 1.0
```

## 5. Rollback 절차

활성화 후 문제 발생 시 즉시 admin-equivalent 동작으로 복귀합니다.

### 5.1 Soft rollback — backend toggle OFF

```bash
# A) ConfigMap patch — security.auth.enabled=false
kubectl edit cm/anycloud-config
# B) restart
kubectl rollout restart deploy/anycloud
```

- 결과: backend interceptor 가 등록되지 않습니다. impersonate_* 필드를 빈 채로 전송합니다. agent 가 base config 를 사용합니다 (admin-equivalent).
- 0 데이터 손실, audit_log.principal 만 다시 null 입니다.

### 5.2 Hard rollback — gateway header strip

```bash
# nginx-ingress
kubectl annotate ingress/anycloud-ingress \
  nginx.ingress.kubernetes.io/configuration-snippet- --overwrite
```

- 결과: gateway 가 X-Forwarded-User 헤더를 안 보냅니다. backend interceptor 가 헤더 없을 시 no-op 입니다.

### 5.3 Agent rollback (impersonate verb 제거 — 권장 X)

```bash
helm --kube-context=<cluster> rollback cluster-agent <prev-revision> -n aipaas-system
```

- 권장 X — RBAC 만 부여되어 있고 invoke 가 없으면 zero cost 입니다. 굳이 제거하지 않아도 됩니다.

## 6. 운영 모니터링

### 6.1 Prometheus alert (degraded metric 기반)

```yaml
groups:
- name: anycloud-impersonation
  rules:
  - alert: HighForbiddenRate
    expr: |
      sum by (cluster, kind) (rate(cluster_agent_degraded_count{reason="FORBIDDEN"}[5m])) > 0.5
    for: 10m
    annotations:
      summary: "Cluster {{ $labels.cluster }} kind {{ $labels.kind }} FORBIDDEN rate high"
      description: "사용자 RBAC 부족 — OidcGroupBinding CR 추가 또는 operator chart label patch (aggregate-to-view-crd-runbook.md §4)"
```

### 6.2 정기 점검

- 매주 — `audit_log` 에 principal=null 행 비율을 측정합니다 (운영자가 toggle OFF 인지 확인).
- 매월 — OIDC group 변화 vs ClusterRoleBinding subjects 일치를 검증합니다.

## 7. 트러블슈팅

| 증상 | 원인 후보 | 대응 |
| --- | --- | --- |
| 모든 사용자 admin-equivalent | toggle OFF 또는 gateway 헤더 누락 | §1.4 검증 + §4 toggle 확인 |
| 사용자가 본인 권한 자원도 못 봄 | agent SA impersonate verb 누락 | §3.1 검증, helm upgrade 재실행 |
| FORBIDDEN 다발 (특정 CRD) | aggregate-to-view 라벨 누락 | OidcGroupBinding CR (aggregate-to-view-crd-runbook.md §4.3 Option D) 또는 `kubectl patch clusterrole` |
| audit_log.principal 모두 null | interceptor 미통과 (async/scheduler) | 정상 동작 — controller 통과 path 만 채워집니다 |
| CommandRequest 응답이 매우 느림 | clientset cache miss (LRU 32 entry 초과) | 사용자 수 측정 → cache size 증가 PR |

## 8. 관련 문서

- `docs/architecture/k8s-impersonation-auth.md` — impersonation pass-through design + trade-off
- `docs/runbooks/aggregate-to-view-crd-runbook.md` — CRD `view` aggregate label 운영
- `docs/architecture/oidc-group-k8s-binding-webhook.md` — OIDC binding 자동화
