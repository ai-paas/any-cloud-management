# Cluster-Agent `resource_policy` Operations Guide

agent ConfigMap 의 `resource_policy` section 을 통한 kind-level 정책 제어입니다. discovery 가 자동 노출하는 모든 kind 위에 보안 layer 를 추가합니다.

## 1. 기본 원리

Agent 의 `LIST_RESOURCES` / `GET_RESOURCE` / `DELETE_RESOURCE` 는 이제 RESTMapper 기반입니다 — 어떤 kind 든
지원합니다. 운영자는 `resource_policy` 로 이 자유도를 제한할 수 있습니다.

```
ConfigMap aipaas-agent-allowlist
    │
    ├── allowed_namespaces   ← 1차 방어선 (namespace gate)
    ├── allowed_commands     ← 2차 방어선 (RPC type gate)
    └── resource_policy      ← 3차 방어선 (kind gate)
            │
            ├── mode: allow_all_discovered | strict
            ├── deny:  [{kind, namespace?}]   ← allow_all_discovered 에서만 의미
            └── allow: [{kind, namespace?}]   ← strict 에서만 의미
```

검사 순서: RPC type → namespace → kind+ns 정책입니다. 어느 단계든 실패면 즉시 거부됩니다. cluster-scoped kind
면 namespace 단계는 skip 됩니다.

## 2. Mode 선택 매트릭스

| 환경 | 권장 mode | 이유 |
|------|-----------|------|
| 개발 / 단일 테넌트 | `resource_policy` 미설정 (nil) | RBAC + namespace allowlist 가 충분. CRD 자동 |
| Self-service 멀티 테넌트 | `allow_all_discovered` + deny 보강 | 새 CRD 자동 노출하되 민감 리소스 차단 |
| 규제 환경 / 금융 | `strict` | 명시 등록된 kind 만 허용. 새 CRD 도 운영자 결정 |
| Air-gapped / 보안 critical | `strict` + `allowed_commands` 축소 | 최소 surface |

## 3. 권장 default deny-list (allow_all_discovered 모드)

```yaml
data:
  resource_policy: |
    mode: allow_all_discovered
    deny:
      # 1. 평문 자격증명 — namespace 무관
      - kind: secrets
      # 2. Pod 직접 조작 (대신 Deployment 권장)
      # - kind: pods   # 단, 운영팀의 troubleshooting 필요 시 enable
      # 3. RBAC 변경 — 일반 사용자는 보면 안 됨
      - kind: roles
      - kind: rolebindings
      - kind: clusterroles
      - kind: clusterrolebindings
      # 4. kube-system 의 모든 자원 격리
      - kind: pods
        namespace: kube-system
      - kind: configmaps
        namespace: kube-system
      - kind: services
        namespace: kube-system
      # 5. cert-manager 의 인증서 / Issuer (있을 경우)
      - kind: certificates
      - kind: certificaterequests
      - kind: orders                 # cert-manager.io/v1
      - kind: challenges
      # 6. webhook 설정 — 변조 시 admission control 우회
      - kind: validatingwebhookconfigurations
      - kind: mutatingwebhookconfigurations
```

운영자가 보안 검토 후 `secrets` 만 제외하는 등 단계적 완화가 가능합니다. 위 list 는 **위험도 우선 정렬** 입니다 —
첫 줄이 가장 critical 합니다.

## 4. Strict 모드 — 명시 allow-list 예시

```yaml
data:
  resource_policy: |
    mode: strict
    allow:
      # 일반 워크로드 자원
      - kind: pods
      - kind: deployments
      - kind: statefulsets
      - kind: daemonsets
      - kind: jobs
      - kind: cronjobs
      # 네트워크
      - kind: services
      - kind: ingresses
      - kind: endpoints
      - kind: endpointslices
      # 설정 (secrets 는 명시 제외)
      - kind: configmaps
      # 스토리지
      - kind: persistentvolumeclaims
      - kind: persistentvolumes
      - kind: storageclasses          # cluster-scoped
      # 메타 / 디버깅 (개발자가 자주 확인)
      - kind: events
      - kind: nodes
      # 운영팀 namespace 한정 — 다른 모든 ns 에서는 거부
      - kind: secrets
        namespace: secret-rotation     # 보안팀만 접근 가능
```

**중요**: strict 는 default deny 입니다 — list 안에 없는 kind 는 자동으로 거부됩니다.

## 5. 멀티 테넌트 시나리오 (테넌트별 namespace 격리)

테넌트 A 와 B 가 각각 `tenant-a` / `tenant-b` namespace 를 사용합니다. cluster 자원은 공유하지 않습니다.

```yaml
data:
  # namespace allowlist 가 1차 격리
  allowed_namespaces: |
    - tenant-a
    - tenant-b
    - monitoring     # 공통 관찰 가능
  resource_policy: |
    mode: allow_all_discovered
    deny:
      # 어떤 테넌트 ns 든 secrets 는 거부 — kubectl-style 직접 조회 금지
      - kind: secrets
      # 다른 테넌트의 자원 보호 — 명시 deny 는 어렵지만 namespace 격리로 커버됨
      # (allowed_namespaces 가 1차 차단)
      # cluster-scoped 자원 격리 — 한 테넌트가 다른 PV / StorageClass 변경 불가
      - kind: persistentvolumes
      - kind: storageclasses
      - kind: customresourcedefinitions
```

## 6. 변경 적용 흐름

```bash
# 1. ConfigMap edit (kubectl)
kubectl -n aipaas-system edit cm aipaas-agent-allowlist

# 2. agent 가 watch 통해 자동 reload — log 로 확인
kubectl -n aipaas-system logs deploy/cluster-agent | grep 'policy updated'

# 3. 적용 확인 (백엔드 호출)
curl -s -X DELETE \
  "$BACKEND/v1/clusters/$CLUSTER/namespaces/$NS/secrets/foo" | jq

# deny 정책 작동 시: degradedReason=RESOURCE_KIND_DENIED 응답
```

agent 의 watch reliability 입니다 — 현재 informer 를 미사용합니다. 그 전까지 ConfigMap
edit 후 reload 가 안 보이면 `kubectl rollout restart deploy/cluster-agent` 로 강제합니다.

## 7. 모니터링

```bash
# RESOURCE_KIND_DENIED 발생 횟수 (backend metrics)
curl -s $BACKEND/actuator/prometheus | grep 'k8s_circuit_fallback.*reason.*RESOURCE_KIND_DENIED'

# agent log 의 정책 거부 라인
kubectl -n aipaas-system logs deploy/cluster-agent | grep -i 'resource_kind_denied'
```

지속적으로 같은 kind 가 거부되면 다음과 같이 대응합니다.
- 정당한 요청 → 정책 조정
- 부정 시도 → audit log 로 caller 추적

## 8. 흔한 함정

### 8-1. kind 이름은 **plural** 로 작성

```yaml
# ✅ 올바름
- kind: pods
- kind: storageclasses
- kind: customresourcedefinitions

# ❌ 잘못됨 — singular 또는 short-name
- kind: pod
- kind: sc
- kind: crd
```

agent 의 RESTMapper 가 항상 plural 로 정규화합니다. ConfigMap 의 rule 도 plural 로 일치시켜야 매칭됩니다.

### 8-2. cluster-scoped kind 에 namespace 적은 deny 는 의미 없음

```yaml
# ❌ 의미 없음 — storageclasses 는 cluster-scoped 라 ns 비교 안 함
- kind: storageclasses
  namespace: kube-system   # 무시됨

# ✅ cluster-scoped 는 namespace 생략
- kind: storageclasses
```

agent 가 RESTMapper 로 scope 판단 후 namespace 비교를 skip 합니다.

### 8-3. strict 모드에서 새 CRD 설치 시 자동 거부

CRD 가 cluster 에 설치되면 discovery 가 즉시 노출됩니다. 하지만 strict 모드의 `allow:` 에 등록되지 않으면
agent 가 거부합니다. 운영자가 새 CRD 사용 전 ConfigMap update 가 필요합니다.

→ 절차:
1. 새 CRD 설치 (Helm / kubectl apply)
2. `kubectl get crd <name>` 으로 확인
3. ConfigMap 에 `- kind: <crd-plural>` 추가
4. backend `GET /v1/clusters/{c}/resource-kinds` 로 노출 확인

### 8-4. `nil` 정책 vs `mode: allow_all_discovered` deny=[]

| 설정 | 동작 |
|------|------|
| `resource_policy` 자체 미설정 (nil) | RBAC + namespace allowlist 만 적용됩니다. backward compat 입니다. |
| `mode: allow_all_discovered` + `deny: []` | 명시적으로 "전부 허용" 입니다 — 의도 표시입니다. 효과는 nil 과 동일합니다. |
| `mode: strict` + `allow: []` | **모든 kind 거부** 입니다 — 사실상 K8s 작업 봉쇄입니다. 의도하지 않았으면 무서운 실수입니다. |

`strict + allow: []` 는 emergency lock-down 용도로만 사용합니다. 정상 운영에선 `nil` 또는 `allow_all_discovered` 를 사용합니다.

## 8-5. Validation Endpoint — `/v1/admin/agent/policy/preview`

운영자가 ConfigMap edit 후 즉시 적용 + 일관성을 검증합니다.

### 사용 예

```bash
# 단일 cluster 의 정책 + warnings
curl -s "$BACKEND/v1/admin/agent/policy/preview?cluster=orb-kubernetes-001" | jq

# CI/CD pre-prod 검증 — HIGH severity 가 하나라도 있으면 실패
HIGHEST=$(curl -s "$BACKEND/v1/admin/agent/policy/preview?cluster=$CLUSTER" \
  | jq -r '.data.highestSeverity')
[ "$HIGHEST" != "HIGH" ] || { echo "❌ Policy has HIGH-severity warnings"; exit 1; }
```

### 응답 schema

```jsonc
{
  "data": {
    "snapshot": {
      "allowedNamespaces": ["monitoring"],
      "allowAllNamespaces": false,
      "allowedCommands": ["LIST_PODS", "LIST_RESOURCES", ...],
      "allowedCharts": ["prometheus-community/kube-prometheus-stack:45.0.0-65.0.0"],
      "resourcePolicy": {
        "mode": "allow_all_discovered",
        "deny": [{"kind": "secrets", "namespace": ""}],
        "allow": []
      },
      "lastReloadAt": "2026-05-20T07:00:00Z",
      "configMapResourceVersion": "12345678"
    },
    "warnings": [
      {"severity": "MEDIUM", "code": "MISSING_RBAC_DENY", "message": "..."}
    ],
    "highestSeverity": "MEDIUM"
  }
}
```

### Warning code 별 조치법 (운영 runbook)

| Code | Severity | 의미 | 조치 |
|------|---------|------|------|
| **MISSING_SECRETS_DENY** | HIGH | `allow_all_discovered` 인데 `secrets` 가 deny 에 없습니다 — 모든 namespace 의 secret 조회가 가능합니다. | `deny:` 에 `- kind: secrets` 를 추가합니다. 멀티 테넌트 / user-facing 환경에선 필수입니다. |
| **STRICT_EMPTY_ALLOW** | HIGH | `mode=strict` + `allow=[]` — 모든 K8s 작업이 차단됩니다. | emergency lock-down 의도면 OK / 아니면 allow 를 채우거나 mode 를 변경합니다. |
| **MISSING_RBAC_DENY** | MEDIUM | RBAC kinds (`roles`/`rolebindings`/`clusterroles`/`clusterrolebindings`) 가 누락되었습니다 — 권한 escalation 이 가능합니다. | 4개 kind 모두 deny 에 추가를 권장합니다. |
| **MISSING_WEBHOOK_DENY** | MEDIUM | webhook configs (`validating`/`mutating`) 가 누락되었습니다 — admission 우회가 가능합니다. | 두 kind 모두 deny 에 추가합니다. |
| **PLURAL_TYPO** | MEDIUM | `kind: pod` 같은 singular 표기 — RESTMapper 매칭이 실패합니다. | plural 형태로 변경합니다 (`pods`). `kubectl api-resources` 로 정확한 plural 을 확인합니다. |
| **UNKNOWN_MODE** | MEDIUM | `mode` 가 `allow_all_discovered`/`strict` 외 값입니다. | 둘 중 하나로 수정합니다. 빈 문자열 = nil = legacy 동작입니다. |
| **KUBE_SYSTEM_UNPROTECTED** | LOW | `kube-system` ns 의 deny rule 이 없습니다. | `- kind: configmaps, namespace: kube-system` 같은 entry 를 추가합니다. |
| **STRICT_DENY_REDUNDANT** | LOW | `mode=strict` 인데 deny 항목이 존재합니다 — strict 는 allow 외 자동 거부됩니다. | 의도 명확화를 위해 deny 항목 제거를 권장합니다. |
| **SENSITIVE_KINDS_IN_ALLOW** | LOW | strict allow 에 `secrets`/RBAC 등 민감 kind 가 포함되어 있습니다. | 의도된 허용인지 확인합니다 — 운영자 결정입니다. |
| **WILDCARD_NAMESPACES** | INFO | `allowed_namespaces=["*"]` — 모든 ns 가 허용됩니다. | 멀티 테넌트면 명시 list 를 권장합니다. dev cluster 면 OK 입니다. |
| **NO_RESOURCE_POLICY** | INFO | `resource_policy` section 미설정 (legacy 동작) 입니다. | 보안 강화를 원하면 본 문서 §3 권장 deny-list 를 적용합니다. |

### 운영 시나리오 예시

**시나리오 1**: 새 cluster 등록 직후
```bash
$ curl -s ".../policy/preview?cluster=new-cluster" | jq '.data.warnings'
[
  {"severity":"INFO","code":"NO_RESOURCE_POLICY","message":"..."},
  {"severity":"INFO","code":"WILDCARD_NAMESPACES","message":"..."}
]
```
→ 초기 default 입니다. 단일 테넌트 dev 환경이면 그대로 OK 입니다. prod 면 §3 의 권장 deny-list 적용 후 재검증합니다.

**시나리오 2**: secrets deny 적용 후 검증
```bash
$ kubectl -n aipaas-system patch cm aipaas-agent-allowlist --type=merge -p '{
  "data": {"resource_policy": "mode: allow_all_discovered\ndeny:\n  - kind: secrets\n"}
}'

$ sleep 5  # agent watch reload 대기
$ curl -s ".../policy/preview?cluster=$CLUSTER" \
  | jq '.data.warnings[] | select(.code=="MISSING_SECRETS_DENY")'
# (empty) — warning 사라짐
```

**시나리오 3**: 운영자가 plural typo 입력 시
```yaml
# ConfigMap 의 잘못된 입력
deny:
  - kind: pod         # ❌ 'pods' 가 맞음
  - kind: storageclass  # ❌ 'storageclasses' 가 맞음
```
```bash
$ curl -s ".../policy/preview?cluster=$CLUSTER" \
  | jq '.data.warnings[] | select(.code=="PLURAL_TYPO")'
{
  "severity": "MEDIUM",
  "code": "PLURAL_TYPO",
  "message": "deny 의 'kind: pod' 는 singular 형태 — ... 'pods' 형태로 변경 권장 ..."
}
```

### lastReloadAt 활용 — watch reload 검증

```bash
# ConfigMap 의 lastModifiedTime
CM_TIME=$(kubectl -n aipaas-system get cm aipaas-agent-allowlist \
  -o jsonpath='{.metadata.managedFields[0].time}')

# agent 가 실제 reload 한 시각
APPLIED=$(curl -s ".../policy/preview?cluster=$CLUSTER" \
  | jq -r '.data.snapshot.lastReloadAt')

echo "ConfigMap edited:    $CM_TIME"
echo "Agent reload applied: $APPLIED"

# 두 시각이 가까우면 watch reload 정상. APPLIED 가 한참 옛날이면 watch 끊김 → restart 필요.
```

## 9. Backward compatibility

본 정책은 **신규 ConfigMap 필드** 입니다. 기존 cluster (`resource_policy` 누락) 는 즉시 영향이 없습니다 —
nil 으로 처리되어 기존 namespace allowlist 기반 동작이 유지됩니다. 신규 cluster 또는 update 시점에
운영자가 의도적으로 활성화합니다.

## 10. 관련 자료

- 코드: `apps/agent/internal/config/allowlist.go` (ResourcePolicy 구조)
- 코드: `apps/agent/internal/controller/dispatcher.go` (checkResourceAccess)
- 백엔드 매핑: `apps/anycloud/src/main/java/com/aipaas/anycloud/domain/kube/internal/KubeServiceImpl.java`
  `classifyDegradedCause` — RESOURCE_KIND_DENIED → degraded reason
- 검증 엔진: `apps/anycloud/src/main/java/com/aipaas/anycloud/domain/agent/policy/AgentPolicyValidator.java`
- Admin endpoint: `GET /v1/admin/agent/policy/preview` (본 문서 §8-5)
- 동적 catalog API: `GET /v1/clusters/{name}/resource-kinds` — 현재 cluster 가 노출하는 모든 kind 입니다.
- Single-kind resolve API: `GET /v1/clusters/{name}/resolve?input=<text>` — 단축이름/오타 보정입니다.
- 관련 runbook: `docs/runbooks/cluster-agent-namespace-wildcard.md`

