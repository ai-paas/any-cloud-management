# Cluster-Agent ConfigMap Migration — `helm.sh/resource-policy: keep`

PUT-only design 의 핵심 전제는 — Helm chart 가 deploy 한 ConfigMap 이 backend PUT 으로 변경된 후 다음 helm upgrade 에 의해 덮어쓰이지 않아야 한다는 것입니다. 신규 chart 는 자동 처리되고, 기존 설치된 chart 들에 대해선 manual migration 이 필요합니다.

## 1. 배경

### 의도된 동작
backend `PUT/PATCH /v1/admin/clusters/{c}/agent-policy` → agent 가 ConfigMap 의 `data` 를 update 합니다.
이후 helm upgrade 가 발생해도 ConfigMap 의 내용이 보존되어야 합니다.

### 구현
chart 의 ConfigMap template 에 annotation 을 추가합니다.
```yaml
metadata:
  annotations:
    helm.sh/resource-policy: keep
```
이 annotation 이 있으면 helm 이 chart upgrade 시 본 리소스를 **삭제하지 않고 다시 만들지도 않습니다**. 즉
backend 가 update 한 변경분이 보존됩니다.

### 위험 시나리오 (annotation 없는 기존 cluster)
chart 의 ConfigMap 에 annotation 이 없는 경우입니다.
1. 운영자가 backend PUT 으로 정책 변경 → ConfigMap data 갱신
2. 누군가 `helm upgrade cluster-agent` 실행
3. **Helm 이 ConfigMap 을 chart 의 default values 로 덮어씁니다** → 운영자 변경분이 소실됩니다
4. agent watch 가 변경 감지 → 옛 정책 적용 → 운영자가 다시 PUT 해야 합니다

## 2. 영향받는 cluster 식별

```bash
# 모든 cluster 의 agent ConfigMap 에 annotation 있는지 확인
for ctx in $(kubectl config get-contexts -o name); do
  echo "=== $ctx ==="
  kubectl --context=$ctx -n aipaas-system get cm aipaas-agent-allowlist \
    -o jsonpath='{.metadata.annotations.helm\.sh/resource-policy}'
  echo
done
```

기대 출력 (정상):
```
=== cluster-A ===
keep
=== cluster-B ===
keep
```

annotation 이 빈 문자열 또는 missing 인 cluster 가 migration 이 필요합니다.

## 3. Migration 방법 — 3가지 옵션

### Option A (권장) — `kubectl annotate` 로 직접 추가
agent 재배포 없이 즉시 적용됩니다. 가장 안전하고 빠릅니다.

```bash
NS=aipaas-system

# 단일 cluster
kubectl -n $NS annotate cm aipaas-agent-allowlist \
  helm.sh/resource-policy=keep --overwrite

# Fleet — 모든 cluster context
for ctx in $(kubectl config get-contexts -o name); do
  echo "Annotating $ctx..."
  kubectl --context=$ctx -n $NS annotate cm aipaas-agent-allowlist \
    helm.sh/resource-policy=keep --overwrite || true
done
```

검증입니다.
```bash
kubectl -n $NS get cm aipaas-agent-allowlist -o yaml | grep -A 1 annotations
```

### Option B — `helm upgrade` 로 chart 적용 (위험)
chart 의 새 버전이 annotation 을 포함하면 helm upgrade 가 본 annotation 을 추가합니다. 하지만 **annotation
추가 직전의 helm upgrade 실행 자체가 기존 ConfigMap 을 덮어씁니다** — 운영자 변경분이 소실됩니다.

**필수 사전 절차**: helm upgrade 전 backend PUT 으로 변경했던 정책을 모두 메모해 두고, upgrade 후
다시 PUT 으로 복원합니다. 또는 Option A 를 사용합니다.

```bash
# values.yaml 의 allowlist 가 운영자 변경분과 일치하는지 확인 후
helm -n $NS upgrade cluster-agent ./cluster-agent --reuse-values
```

### Option C — `helm upgrade --reuse-values` + 사전 backup
operational safety net 패턴 — 어떤 변경분도 잃지 않기 위함입니다.

```bash
NS=aipaas-system
BACKUP=/tmp/agent-allowlist-$(date +%Y%m%d-%H%M%S).yaml

# 1) 현재 ConfigMap snapshot 백업
kubectl -n $NS get cm aipaas-agent-allowlist -o yaml > $BACKUP

# 2) Option A 의 annotate 명령 실행 (이 시점부터 helm upgrade 안전)
kubectl -n $NS annotate cm aipaas-agent-allowlist \
  helm.sh/resource-policy=keep --overwrite

# 3) Helm upgrade — 이제 keep annotation 때문에 ConfigMap 변경 안 됨
helm -n $NS upgrade cluster-agent ./cluster-agent

# 4) (선택) 사전 backup vs 현재 비교 — 모든 키 동일해야 함
diff <(kubectl -n $NS get cm aipaas-agent-allowlist -o yaml) $BACKUP
```

## 4. 자동화 — backend startup hook (선택)

운영자가 매번 명령 실행할 부담을 회피하기 위함입니다 — backend startup 시 모든 cluster 의 ConfigMap 에 annotation
누락을 검출 + 자동 추가합니다.

```java
@Component
@RequiredArgsConstructor
public class AgentConfigMapMigrationRunner implements ApplicationRunner {

	private final ClusterService clusterService;
	private final KubeResourceService kubeResourceService;
	private final AgentCommandRouter agentCommandRouter;

	@Override
	public void run(ApplicationArguments args) {
		// 모든 ACTIVE cluster 의 ConfigMap annotation 확인 → 없으면 ANNOTATE_CONFIGMAP 명령으로 추가.
		// 별도 agent command 필요 — 본 문서의 future work.
	}
}
```

(이 자동화 자체가 별도 작업입니다. 현재는 manual 입니다.)

## 5. 검증 — migration 완료 확인

운영자가 PUT 으로 정책 변경 후 helm upgrade 가 변경분을 보존하는지 end-to-end 로 검증합니다.

```bash
NS=aipaas-system
CLUSTER=orb-kubernetes-001

# 1) Backend PUT 으로 정책 변경 — 테스트용 marker
curl -X PATCH "$BACKEND/v1/admin/clusters/$CLUSTER/agent-policy" \
  -H 'Content-Type: application/json' \
  -d '{"allowedNamespaces":["migration-test-marker"]}'

# 2) ConfigMap 에 변경 반영 확인
kubectl -n $NS get cm aipaas-agent-allowlist \
  -o jsonpath='{.data.allowed_namespaces}'
# → "migration-test-marker" 포함

# 3) Helm upgrade 실행
helm -n $NS upgrade cluster-agent ./cluster-agent --reuse-values

# 4) ConfigMap 의 marker 가 여전히 있는지
kubectl -n $NS get cm aipaas-agent-allowlist \
  -o jsonpath='{.data.allowed_namespaces}'
# → annotation 있으면 여전히 "migration-test-marker"
# → annotation 없으면 chart default 의 namespaces 로 reset (migration 필요)

# 5) 정리 — marker 제거
curl -X PATCH "$BACKEND/v1/admin/clusters/$CLUSTER/agent-policy" \
  -H 'Content-Type: application/json' \
  -d '{"allowedNamespaces":["monitoring"]}'   # 운영 정책으로 복원
```

## 6. 알려진 한계 / 후속 작업

- **Manual 절차**: 신규 cluster 등록 시 chart 의 새 template (annotation 포함) 이 자동 적용되지만,
  **기존 cluster** 는 운영자가 명시적으로 위 절차를 실행해야 합니다.
- **자동화 제안**: backend startup 시 fleet 전체 ConfigMap annotation 자동 backfill (위 §4 참조) 입니다.
- **Helm uninstall 시 ConfigMap 잔존**: `resource-policy: keep` 은 chart 삭제 후에도 ConfigMap 을 보존합니다.
  cluster 완전 정리 시 `kubectl delete cm aipaas-agent-allowlist -n aipaas-system` 별도 실행이 필요합니다.

## 7. 관련 자료

- chart: `apps/agent/deploy/helm/cluster-agent/templates/configmap.yaml`
- backend PUT: `apps/anycloud/src/main/java/com/aipaas/anycloud/domain/agent/web/AdminAgentPolicyController.java`
- runbook (정책 가이드): `docs/runbooks/cluster-agent-resource-policy.md`
- runbook (wildcard 진단): `docs/runbooks/cluster-agent-namespace-wildcard.md`
