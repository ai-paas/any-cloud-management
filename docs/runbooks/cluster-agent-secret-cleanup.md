# Cluster-Agent K8s Secret Cleanup

Backend 에서 cluster 가 삭제된 후, 해당 cluster 의 K8s namespace 에 남는 agent 관련 Secret 들의 정리 절차입니다. Backend 가 더 이상 kube 자격을 가지지 않으므로 수동/Helm 기반 cleanup 이 필요합니다.

## 1. 배경

Cluster 가 backend (`DELETE /v1/clusters/{name}`) 에서 제거되면 cascade cleanup 으로
`cluster_agent` row 가 함께 삭제됩니다 (다음도 참고: `docs/runbooks/cluster-agent-mtls-production.md`).
그러나 **target cluster 의 K8s namespace `aipaas-system`** 에는 세 개의 Secret 이 남아 있고,
backend 는 자격이 없어 직접 정리할 수 없습니다.

| Secret 이름                  | 소유자       | 내용                                                | 잔존 영향                                                       |
|------------------------------|--------------|-----------------------------------------------------|-----------------------------------------------------------------|
| `cluster-agent-mtls`         | agent        | `tls.crt`, `tls.key`, `ca.crt`, `serial`, `not_after` | agent 재기동 시 stale cert load → register 실패                |
| `cluster-agent-identity`     | agent        | 60-day opaque identity token                        | agent 가 dead token 으로 stream 시도 → PERMISSION_DENIED      |
| `aipaas-agent-bootstrap`     | operator     | 단기 registration JWT (~10분 TTL)                   | 만료 후 무의미. helm upgrade 시 재주입                          |

세 Secret 모두 Helm chart 의 `helm.sh/resource-policy: keep` annotation 으로 보호되어 있어
`helm uninstall` 만으로는 사라지지 않습니다 (의도된 design — agent 재기동 시 cert/identity 보존).

## 2. 언제 정리해야 하나

| 시나리오                                    | 정리 필요?  | 비고                                                          |
|---------------------------------------------|-------------|---------------------------------------------------------------|
| Cluster 완전 폐기 (K8s 자체도 삭제)         | 불필요      | namespace 가 사라지면서 동반 삭제                              |
| Cluster 유지하지만 backend 등록만 해제      | **필요**    | 본 runbook 의 대상. 같은 cluster 를 다른 backend 에 붙일 때 필수 |
| Cluster 를 동일 backend 에 재등록 예정      | 권장        | 새 registration_token + 새 cert 발급 받도록 stale state 제거    |
| Backend 만 재배포 (cluster 그대로)          | 불필요      | DB 의 `backend_ca` 가 영구화 — 기존 cert 그대로 유효            |

## 3. 정리 절차 — Manual (kubectl)

운영자가 target cluster 에 대한 kubeconfig 를 갖고 있을 때 사용합니다.

```bash
# 1) Cluster 가 backend 에서 삭제되었는지 확인
curl -s "$BACKEND/v1/clusters/orb-001" | jq '.data'
# → 404 또는 ClusterNotFoundException 이어야 정상

# 2) Target cluster 의 agent state 확인 (선택)
kubectl --kubeconfig ~/.kube/orb-001 -n aipaas-system get secrets \
  | grep -E "cluster-agent-(mtls|identity)|aipaas-agent-bootstrap"

# 3) agent deployment 먼저 중지 — Secret 삭제 후 자동 재생성 방지
kubectl --kubeconfig ~/.kube/orb-001 -n aipaas-system scale deployment/cluster-agent --replicas=0

# 4) 세 Secret 삭제
kubectl --kubeconfig ~/.kube/orb-001 -n aipaas-system delete secret \
  cluster-agent-mtls \
  cluster-agent-identity \
  aipaas-agent-bootstrap \
  --ignore-not-found

# 5) (선택) helm release 도 정리 — keep annotation 무시
helm --kubeconfig ~/.kube/orb-001 -n aipaas-system uninstall cluster-agent

# 6) (선택) namespace 자체 삭제 — agent 관련 ConfigMap, ServiceAccount, RBAC 까지 한 번에
kubectl --kubeconfig ~/.kube/orb-001 delete namespace aipaas-system
```

## 4. 정리 절차 — Job 기반 (대규모 fleet)

여러 cluster 를 한 번에 정리할 때 사용합니다.

```bash
#!/usr/bin/env bash
# scripts/ops/cleanup-orphan-agents.sh
# 사용: ./cleanup-orphan-agents.sh <cluster-name> <kubeconfig-path>

set -euo pipefail
CLUSTER="$1"
KUBECONFIG_PATH="$2"

echo "Cleaning orphan agent state on cluster=${CLUSTER}"

# Backend 에 정말로 삭제되었는지 double-check (실수 방지)
HTTP=$(curl -s -o /dev/null -w "%{http_code}" "$BACKEND/v1/clusters/${CLUSTER}")
if [[ "$HTTP" != "404" ]]; then
  echo "ABORT: cluster=${CLUSTER} 가 아직 backend 에 존재 (HTTP=${HTTP})"
  exit 1
fi

kubectl --kubeconfig "${KUBECONFIG_PATH}" -n aipaas-system \
  scale deployment/cluster-agent --replicas=0 --timeout=30s || true

kubectl --kubeconfig "${KUBECONFIG_PATH}" -n aipaas-system delete secret \
  cluster-agent-mtls cluster-agent-identity aipaas-agent-bootstrap \
  --ignore-not-found --timeout=30s

echo "OK ${CLUSTER}"
```

## 5. 자동화 — 주기적 reconciler (선택)

운영팀이 backend 의 `cluster_agent` row 와 K8s Secret 의 cross-reference 를 주기적으로 검사하고
싶다면, 별도 Job 으로 다음과 같이 구현이 가능합니다 (현재는 미구현 — 후속 작업 후보).

```
[orphan-secret-sweeper]
  for cluster in known_kubeconfigs:
    backend_has = curl $BACKEND/v1/clusters/$cluster → 200?
    cluster_has = kubectl get secret cluster-agent-mtls -n aipaas-system → 0?
    if cluster_has and not backend_has:
        emit alert "orphan agent state on cluster=$cluster"
```

`kubeconfig` 의 출처를 안전하게 운영할 수 있어야 (Vault / sealed-secrets) 자동화가 가능합니다.
현재는 manual cleanup 만 지원합니다.

## 6. 확인 / 검증

정리 후 확인합니다.

```bash
# Secret 없어졌나
kubectl --kubeconfig ~/.kube/orb-001 -n aipaas-system get secrets \
  cluster-agent-mtls cluster-agent-identity aipaas-agent-bootstrap 2>&1 \
  | grep -E "NotFound|not found"
# → 모두 NotFound 면 정상

# Backend DB 도 깨끗한가
curl -s "$BACKEND/v1/clusters/orb-001/cert" | jq '.'
# → ClusterNotFoundException 이어야 정상
```

## 7. 알려진 한계 / 후속 작업

- **자동 reconciler 미구현** — backend 가 target cluster 의 kubeconfig 를 능동적으로 보관하지
  않으므로 cluster 삭제와 동시에 Secret cleanup 트리거가 불가합니다. 후속: cluster 등록 시 kubeconfig
  옵션 보관 → 삭제 직전 cleanup hook 호출입니다.
- **`helm.sh/resource-policy: keep` 의 부작용** — 동일 cluster 에 다른 backend (예: stage / prod
  분리) 의 agent 를 설치하면 stale Secret 이 새 backend 의 CA 와 mismatch 를 유발합니다. 환경 전환 시
  본 runbook 의 manual cleanup 이 필수입니다.
- **identity_token 의 TTL** — 60일이지만 grace 후에도 backend DB 에 row 가 없으면 무의미합니다. backend
  레코드 정리가 일등 보안 controlplane 이고, Secret 정리는 정리 부산물입니다.

## 8. 관련 자료

- 코드: `apps/anycloud/src/main/java/com/aipaas/anycloud/domain/cluster/internal/ClusterServiceImpl.java` (cluster delete cascade)
- 코드: `apps/agent/internal/core/certstore.go` (cluster-agent-mtls Secret 소유자)
- 코드: `apps/agent/internal/core/identitystore.go` (cluster-agent-identity Secret 소유자)
- Helm: `apps/agent/deploy/helm/cluster-agent/templates/secret.yaml`
- 관련 runbook: `docs/runbooks/cluster-agent-mtls-production.md`
- 관련 runbook: `docs/runbooks/cluster-agent-resource-policy.md` (helm.sh/resource-policy 운영 정책)
