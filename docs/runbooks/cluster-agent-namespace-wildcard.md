# Cluster-Agent Namespace Wildcard Troubleshooting

> 증상: `allowed_namespaces: ["*"]` 가 ConfigMap 에 있는데도 backend 응답에 `NAMESPACE_NOT_ALLOWED` 가 나옴.
> 결론: **agent 코드는 verified correct.** 거의 100% deployment 이슈 (stale image / cached pod).

## 1. 빠른 진단 명령

```bash
NS=aipaas-system

# 1) 현재 agent pod 가 쓰는 이미지 + 시작 시각
kubectl -n $NS get pod -l app.kubernetes.io/name=cluster-agent \
  -o jsonpath='{range .items[*]}{.metadata.name}{"\t"}{.spec.containers[0].image}{"\t"}{.status.startTime}{"\n"}{end}'

# 2) agent 로그에서 wildcard / allowlist 적용 메시지 확인
kubectl -n $NS logs deploy/cluster-agent --tail=200 | grep -iE 'allowlist|wildcard|AllowAll|policy updated'

# 3) ConfigMap reload 가 발생했는지
kubectl -n $NS logs deploy/cluster-agent | grep 'policy updated' | tail -5

# 4) ConfigMap 의 현재 내용
kubectl -n $NS get cm aipaas-agent-allowlist -o yaml | grep -A 5 allowed_namespaces

# 5) agent pod 상태 (CrashLoopBackOff / Running)
kubectl -n $NS get pod -l app.kubernetes.io/name=cluster-agent
```

기대 출력 (정상):
```
agent 로그: "allowlist: policy updated" + namespaces 카운트 변경 기록
ConfigMap:  allowed_namespaces: ["*"]
Pod:        Running, 재시작 카운트 안정
```

## 2. 시나리오별 fix

### 2-1. Image 가 wildcard fix 이전 SHA

ConfigMap 은 멀쩡한데 agent 가 옛날 이미지로 동작하는 경우입니다 — `imagePullPolicy: IfNotPresent` + 동일 태그로 빌드한 경우 K8s 가 cached layer 를 그대로 사용합니다.

```bash
# (a) 강제 rollout — pull policy 가 Always 가 아니어도 새 pod 가 image 를 다시 가져옴
kubectl -n $NS rollout restart deployment/cluster-agent

# (b) image pull policy 변경 (개발 환경)
kubectl -n $NS set image deployment/cluster-agent agent=<registry>/<image>:<new-sha>

# (c) helm chart 의 image.tag 를 새 SHA 로 bump 후 helm upgrade
helm upgrade cluster-agent ./apps/anycloud/src/main/resources/charts/cluster-agent \
    --namespace $NS \
    --set image.tag=$(git rev-parse --short HEAD)
```

검증입니다.
```bash
kubectl -n $NS rollout status deployment/cluster-agent
kubectl -n $NS logs deploy/cluster-agent | grep -E 'cluster-agent starting|wildcard|AllowAll'
```

### 2-2. Agent pod 가 CrashLoopBackOff (JWT signing key 이슈 등)

```bash
# 종합 진단
kubectl -n $NS describe pod -l app.kubernetes.io/name=cluster-agent | head -50

# 최근 crash 시점 로그
kubectl -n $NS logs --previous deploy/cluster-agent | tail -50
```

JWT 관련 `bootstrap failed: JWT signature does not match` 가 보이면 → JWT signing key 가 backend DB 에 영속화되어 있어야 합니다. backend migration 적용 + restart 됐는지 확인합니다.

### 2-3. ConfigMap 변경이 reload 안 됨 (watch 끊김)

agent 의 ConfigMap watch 가 silent 하게 죽었을 수 있습니다. 로그에 `policy updated` 가 없으면:

```bash
# agent pod 재시작 → LoadOnce 가 강제 호출됨
kubectl -n $NS rollout restart deployment/cluster-agent
```

`Loader.Watch` 는 stream close 시 caller 가 재시작해야 합니다 (informer 미사용).

### 2-4. ServiceAccount RBAC 가 ConfigMap read 불가

```bash
# 권한 확인
kubectl -n $NS get role,rolebinding | grep -i 'agent\|allowlist'

# 부족하면 helm chart 의 RBAC 템플릿 검토 — 보통 chart 가 이미 부여
kubectl -n $NS auth can-i get configmaps --as=system:serviceaccount:$NS:aipaas-agent-core
```

기대: `yes` 입니다. `no` 면 chart 의 Role / RoleBinding 이 누락된 것입니다.

## 3. 코드 검증 (참고용)

Agent 의 wildcard 처리 코드 위치 + 로직입니다 (이미 verified).

- **`apps/agent/internal/config/allowlist.go:243`** — `"*"` → `AllowAllNamespaces = true` 세팅:
  ```go
  for _, n := range nss {
      if strings.TrimSpace(n) == "*" {
          policy.AllowAllNamespaces = true
          continue
      }
      policy.Namespaces[n] = struct{}{}
  }
  ```

- **`apps/agent/internal/config/allowlist.go:187`** — `IsNamespaceAllowed()` 가 short-circuit:
  ```go
  if a.AllowAllNamespaces {
      return true
  }
  ```

- **`apps/agent/internal/config/allowlist_test.go:142-167`** — `TestParseConfigMap_WildcardNamespace` 가 `["*"]` + 임의 namespace (`default`, `kube-system`, `monitoring`) 통과를 검증합니다. CI 통과 = code 정상입니다.

따라서 **production 응답에서 `NAMESPACE_NOT_ALLOWED` 가 나오면 = deployed 이미지/pod 의 문제** 입니다. 코드 reproduce 시도하지 말고 위 2 단계로 진행합니다.

