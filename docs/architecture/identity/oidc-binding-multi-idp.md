# OIDC Group Binding — Multi-IdP 호환 모델

Keycloak / pocket-id / Google / Microsoft Entra / generic OIDC 를 동일 starter API 로 지원하는
선언적 (declarative) RBAC binding 모델. starter 의 catalog (`binding-templates.yaml`) 에 group →
ClusterRole 매핑을 선언하면 cluster-agent reconciler 가 K8s ClusterRoleBinding 으로 적용.

> matching 은 `matchExact` 만 지원 — regex (`matchExpression`) 은 backend observation state 가
> 필요해 starter 의 DB-free / stateless 모델과 충돌하므로 도입 X. dynamic team naming 은
> IdP-native pattern (group hierarchy / attribute mapper) 으로 처리. § 2 참조.

## 1. 설계 원칙

| 원칙 | 이유 |
|---|---|
| **IdP-specific admin API 의존 없음** | OIDC 표준에 group enumeration 없음. provider 별 어댑터는 N배 코드 + N개 credential lifecycle |
| **선언적 (declarative) 만** | matchExact only — 모든 IdP 호환, cold start 없음, observation state 없음 |
| **K8s native 패턴** | K8s RBAC 가 group enumeration 안 하듯 우리도 하지 않음 (선언만) |
| **K8s + Keycloak = single truth** | backend table 없음. starter 가 stateless. binding 자체는 K8s ClusterRoleBinding 에만 존재. 운영자 정책은 starter 의 catalog (binding-templates.yaml) |

## 2. matching — matchExact (선언적)

```yaml
spec:
  oidcGroupSelector:
    matchExact: ["platform-admins", "platform-leads"]
  targetSubjects:
    - kind: Group
      name: $oidcGroup
  roleRefs:
    - kind: ClusterRole
      name: admin
      scope: ClusterScope
```

- backend 가 즉시 K8s 에 ClusterRoleBinding 을 생성합니다 (observation 무관).
- **cold start 가 없습니다** — 첫 사용자가 dial in 하기 전에 RBAC 가 준비됩니다.
- 모든 IdP 호환입니다 (group name 만 알면 됩니다).

dynamic team naming 운영 (팀이 자주 추가되는 환경) 은 **IdP-native pattern** 으로 처리:

#### Pattern A — Group hierarchy + matchExact prefix

Keycloak / 일반 OIDC IdP 에서 group 을 hierarchy 로 명명. starter 의 matchExact 가 leaf 별로 매칭.

```yaml
# Keycloak group: /teams/platform, /teams/data, /teams/ml
# JWT claim "groups": ["/teams/platform"]
templates:
  - id: any-team-default-edit
    oidcGroupSelector:
      matchExact: ["/teams/platform", "/teams/data", "/teams/ml"]  # leaf 명시
    targetSubjects:
      - { kind: Group, name: "$oidcGroup" }
    roleRefs:
      - { kind: ClusterRole, name: edit }
```

새 팀 추가 시: Keycloak 에 group + binding-templates.yaml 에 leaf 1줄 추가 (PR-driven).

#### Pattern B — Group attribute + protocol mapper (Keycloak-specific)

Keycloak group 에 attribute (`k8s-role: admin`) 부착 → protocol mapper 가 JWT claim 에 자동 추가.
binding template 은 claim 만 보면 됨 (group enumeration 없이도 dynamic).

> 본 패턴은 D-2 이후 별도 PR 으로 `claimSelector` SPI 추가 시 활성. 현재 starter 는 matchExact only.

#### 운영 가이드

- 팀 수 < 50 → matchExact 정적 catalog 충분 (Pattern A 의 leaf 명시)
- 팀 수 > 50 또는 매주 새 팀 → Keycloak group attribute 활용 (Pattern B, 향후)
- regex (matchExpression) → **사용 안 함**

## 3. Selector.kind — Group vs User

multi-IdP 의 개별 user RBAC override 를 지원합니다.

| kind | 매칭 대상 | 용도 | 예시 |
|---|---|---|---|
| **Group** (default) | OIDC group claim | 일반 RBAC | platform-admins, team-* |
| **User** | OIDC sub (또는 mapping) | break-glass, 개별 권한 | alice@company.com |

```yaml
# Group binding (default)
spec:
  oidcGroupSelector:
    matchExact: ["platform-admins"]   # kind 생략 → Group
    
# User binding
spec:
  oidcGroupSelector:
    kind: User
    matchExact: ["alice@company.com"]
```

target.kind 미명시 시 selector.kind 가 자동 상속됩니다 — 운영자 boilerplate 가 감소합니다.

## 3.5. Cross-cluster 정책 — tieredRoleRefs (default form)

team-X 가 prod cluster 에선 view, dev cluster 에선 admin 권한을 갖는 등 cluster tier 별 다른
권한이 필요할 때.

```yaml
# binding-templates.yaml — 운영자 작성 form (권장)
templates:
  - id: team-x
    oidcGroupSelector:
      matchExact: [team-x]
    tieredRoleRefs:
      tierLabel: anycloud.io/tier             # cluster.labels 의 key
      tiers:
        prod: [{ kind: ClusterRole, name: view,  scope: ClusterScope }]
        stg:  [{ kind: ClusterRole, name: edit,  scope: ClusterScope }]
        dev:  [{ kind: ClusterRole, name: admin, scope: ClusterScope }]
```

starter 가 내부적으로 tier 별 `BindingTemplate` N개로 expand (id = `team-x@prod`, `team-x@dev`,
...). expanded form 의 internal 표현은 `forClusters: matchLabels` 사용.

### advanced form — forClusters: matchLabels

tier 외 label 매칭 필요 시 (region, customer 등):

```yaml
templates:
  - id: ops-fleet-admin
    oidcGroupSelector:
      matchExact: [ops-team]
    forClusters:
      matchLabels: {}                          # 모든 cluster
    roleRefs:
      - { kind: ClusterRole, name: cluster-admin, scope: ClusterScope }
```

→ 운영자 작성 form 으로는 **tieredRoleRefs 가 default**, advanced 만 forClusters.

## 4. IdP-별 claim 매핑

각 OIDC provider 의 group/user claim 위치는 다음과 같습니다 (참고).

| IdP | Group claim | User claim |
|---|---|---|
| **Keycloak** | `groups` (custom mapper 필수) | `preferred_username` 또는 `email` |
| **pocket-id** | `groups` | `email` |
| **Google Workspace** | `hd` + custom (Admin SDK 필요) | `email` |
| **Microsoft Entra ID** | `groups` 또는 `roles` | `preferred_username` (UPN) |
| **Generic OIDC** | `groups` (조직 의존) | `sub` 또는 `email` |

backend 의 `ImpersonationInterceptor` 가 gateway 가 forward 한 헤더에서 추출합니다 — IdP-specific 매핑은 gateway 책임입니다.

## 5. K8s impersonation flow (구체)

```
[OIDC Provider]                   [Gateway]                       [Backend]
사용자 alice 로그인        JWT 발급          ID token              X-User: alice
groups: [team-platform]                       claim 추출            X-Groups: team-platform
                                              헤더 변환              ───────┐
                                                                            ▼
                                                          ImpersonationInterceptor
                                                          ImpersonationContext.set(alice, [team-platform])
                                                                            │
                                                                            ▼
                              gRPC                       Backend → CommandRequest
                                                          impersonate_user: alice
                                                          impersonate_groups: [team-platform]
                                                                            │
                                                                            ▼ gRPC
                              [cluster-agent]
                              CommandRequest → K8s API
                              Impersonate-User: alice
                              Impersonate-Group: team-platform
                                                                            │
                                                                            ▼
                              [K8s API server]
                              RBAC check: Group team-platform 에 RoleBinding(edit) 있음?
                                YES (agent reconciler 가 미리 생성)
                              → 허용
```

핵심은 K8s 의 RBAC binding 이 user 도착 전에 존재해야 한다는 점입니다. **matchExact 는 즉시 생성을 보장합니다**.

## 6. 흔한 패턴 cookbook

### 패턴 A — 운영 admin 그룹

```yaml
name: platform-admins
spec:
  oidcGroupSelector:
    matchExact: ["platform-admins"]
  targetSubjects:
    - kind: Group
      name: $oidcGroup
  roleRefs:
    - kind: ClusterRole
      name: admin
      scope: ClusterScope
```

### 패턴 B — 팀 single namespace edit

```yaml
name: team-edit
spec:
  oidcGroupSelector:
    matchExact: ["team-platform", "team-data", "team-ml"]    # 팀 leaf 명시
  targetSubjects:
    - kind: Group
      name: $oidcGroup
  roleRefs:
    - kind: ClusterRole
      name: edit
      scope: Namespaced
      namespaces: ["$oidcGroup"]      # team-platform → ns team-platform
```

팀 수가 많아 leaf 일일 명시가 부담이면 Keycloak group hierarchy (`/teams/<name>`) + protocol
mapper 로 JWT claim 에 자동 부착 (§ 2 Pattern A/B 참조).

### 패턴 C — 개별 user break-glass

```yaml
name: alice-cluster-admin
spec:
  oidcGroupSelector:
    kind: User
    matchExact: ["alice@company.com"]
  targetSubjects:
    - name: $oidcGroup
  roleRefs:
    - kind: ClusterRole
      name: cluster-admin
      scope: ClusterScope
```

### 패턴 D — viewer 전체 (모든 인증 사용자)

K8s `system:authenticated` group 을 활용합니다 — OIDC group binding 이 아니라 별도
ClusterRoleBinding 을 직접 적용합니다.

```yaml
# kubectl 직접 적용 (OIDC group binding 외 경로)
apiVersion: rbac.authorization.k8s.io/v1
kind: ClusterRoleBinding
metadata:
  name: authenticated-view
subjects:
  - kind: Group
    name: system:authenticated
    apiGroup: rbac.authorization.k8s.io
roleRef:
  kind: ClusterRole
  name: view
  apiGroup: rbac.authorization.k8s.io
```

## 7. Cluster-agent 와의 관계

**Channel A** — RBAC 설치 (admin path) 입니다.
- agent 의 자체 ServiceAccount 를 사용합니다 (in-cluster RBAC).
- agent reconciler 가 ClusterRoleBinding 을 생성/삭제합니다.
- 모든 cluster 의 K8s API 통신은 agent 를 경유해 일관성을 유지합니다.

**Channel B** — User 작업 (impersonation path) 입니다.
- backend → agent (gRPC) → K8s API (Impersonate-User/Group) 입니다.
- impersonation pass-through 로 구현합니다.
- Channel A 가 만든 RBAC 가 Channel B 의 권한을 결정합니다.

두 channel 은 독립입니다. 모두 cluster-agent 를 경유합니다.

## 8. 운영 권고

| 시나리오 | 권장 매칭 | 이유 |
|---|---|---|
| 알려진 group (admin, audit, security 등) | matchExact | cold start 없음, 안전 |
| 동적 team (team-*) — 적은 수 (≤50) | matchExact 로 leaf enumerate | catalog PR 1줄. cold start 없음 |
| 동적 team — 대량 (≥50, 잦은 추가) | Keycloak group attribute + protocol mapper (§ 2 Pattern B) | enumerate 부담 회피 |
| 개별 user override | matchExact + kind=User | 명시적 audit |
| 정규식 매칭 user | ❌ 권장 안 함 | 보안 위험 (예측 못 한 매칭) |
