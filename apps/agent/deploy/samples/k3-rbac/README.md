# Impersonation RBAC Sample Bindings

> 운영자가 impersonation pass-through 활성화 시 base 로 사용할 수 있는
> ClusterRoleBinding / RoleBinding YAML 샘플.

## 활성화 순서

전체 절차는 `docs/runbooks/impersonation-production-activation.md` 참조.

1. **Gateway 측 X-Forwarded-User / X-Forwarded-Groups 헤더 첨가** — nginx-ingress / Envoy / Traefik 설정.
   대신 단순 path-based sticky session 만 원하면:
   ```
   nginx.ingress.kubernetes.io/upstream-hash-by: "$arg_cluster_id$uri"
   ```
2. **K8s ClusterRoleBinding 작성** — 본 폴더의 sample 을 base 로 customize.
3. **Backend 활성화** — `security.auth.enabled=true` + restart.
4. **Agent helm upgrade** — `impersonate` verb 가 포함된 chart 로 upgrade.

## Sample 파일

| 파일 | 시나리오 |
| --- | --- |
| `01-developer-team-view.yaml` | OIDC group `dev-team` → cluster-wide read-only |
| `02-ops-team-edit.yaml` | OIDC group `ops-team` → cluster-wide mutating |
| `03-namespace-scoped-team.yaml` | 특정 user → 특정 namespace edit |

## 검증

```bash
# A) 사용자 권한 확인 (impersonation 시뮬레이션)
kubectl auth can-i list pods \
  --as=alice@example.com --as-group=dev-team
# 기대: yes (view 권한)

kubectl auth can-i delete pods \
  --as=alice@example.com --as-group=dev-team
# 기대: no (view 는 mutating 불가)

# B) anycloud 통해 호출 — backend 로그에서 ImpersonationInterceptor 동작 확인
curl -H "X-Forwarded-User: alice@example.com" \
     -H "X-Forwarded-Groups: dev-team" \
     "$BASE/v1/clusters/<id>/namespaces/default/pods?pageSize=10" | \
  jaq '.data | {degraded, degradedReason, returnedItemCount}'

# C) audit_log 에 principal 기록
mysql -e "SELECT principal, action, created_at \
  FROM anycloud.audit_log WHERE principal IS NOT NULL ORDER BY created_at DESC LIMIT 5;"

# D) Micrometer metric — FORBIDDEN count 증가
curl -s "$BASE/actuator/prometheus" | grep cluster_agent_degraded_count
```

## 관련 문서

- `docs/runbooks/impersonation-production-activation.md` — 전체 활성화 절차
- `docs/architecture/k8s-impersonation-auth.md` — impersonation design
- `docs/runbooks/aggregate-to-view-crd-runbook.md` — third-party CRD 권한
- `apps/agent/deploy/helm/cluster-agent/templates/view-extras.yaml` — anycloud-view-extras catalog
