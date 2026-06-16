# Helm Repository Sync

Backend 에 등록된 helm-repo (internal ChartMuseum + external public) 를 모두 cluster-agent 의
helm SDK 에 자동으로 sync 합니다. observability auto-install 의 hardcoded `prometheus-community` alias 가
internal mirror 로 redirect 될 수 있습니다.

## 구조

```
┌─────────────┐  POST /helm-repos              ┌──────────────┐
│   Backend   │ ─────────────────────────────► │  helm_repo   │
│             │                                │  + source    │
│             │                                │  + tags      │
└─────────────┘                                └──────────────┘
       │                                              │
       │ ApplicationReady                            HelmRepoListSerializer
       │ ↓                                              ↓ JSON array
       │ HelmRepoAutoSeedRunner                       │
       │  (default external: prometheus-community,   │
       │   grafana, bitnami)                         │
       │                                              ↓
       │           Admin PUT /agent-policy            │
       │           ───────────────────────────────────┤
       │                                              │
       │                                              ▼
       │                                   ┌──────────────────┐
       │                                   │   AgentCommand   │
       │                                   │   Router         │
       │                                   │ .applyAgentConfig│
       │                                   │  (helm_reposJSON)│
       │                                   └─────────┬────────┘
       │                                              │ gRPC
       │                                              ▼
       │                                   ┌──────────────────┐
       │                                   │ cluster-agent    │
       │                                   │ apply_config.go  │
       │                                   │  ├ ConfigMap     │
       │                                   │  │  helm_repos   │
       │                                   │  │   key write   │
       │                                   │  └ helm.Sync     │
       │                                   │    Repositories  │
       │                                   │    (RepositoryFile│
       │                                   │     merge)       │
       │                                   └──────────────────┘
       │
       │ Observability install
       │
       └► ObservabilityStackInstaller
            ├ InstallRequest(namespace, releaseName, chartVersion,
            │                valuesJson, repo, chart)
            │     repo == null → agent default "prometheus-community"
            │     repo == "chart-museum-internal" → internal redirect
            ▼
         (agent observability.go 가 cmd.repo override 우선 사용)
```

## 컴포넌트

### Backend

| 컴포넌트 | 책임 |
|---------|------|
| `HelmRepoEntity` | `source` (INTERNAL/EXTERNAL) + `tags` 컬럼 |
| `HelmRepoSource` enum | INTERNAL / EXTERNAL (MIRROR 는 INTERNAL 의 한 종류, tags 로 표현) |
| `HelmRepoSeedProperties` | `helm-repo.auto-seed.enabled` + `repos[]` |
| `HelmRepoAutoSeedRunner` | `ApplicationReadyEvent` 시점 멱등 seed |
| `HelmRepoListSerializer` | DB → JSON array string |
| `AdminAgentPolicyController` | apply-policy 시 helm_repositories 도 push |
| `AgentCommandRouter.applyAgentConfig` | 6-param overload (helmRepositoriesJson) |
| `ObservabilityStackInstaller.InstallRequest` | `repo` / `chart` optional 필드 |
| `ClusterPolicyBootstrapper.pushOnActive` | Cluster PENDING_AGENT → ACTIVE 전환 시 자동 호출 (`AgentBootstrapServiceImpl.backfillClusterFromAgent` 가 transition 직후 trigger) |
| `HelmRepoChangedEvent` | HelmRepo CRUD 시 publish — `@TransactionalEventListener(AFTER_COMMIT)` + `@Async` broadcast |
| `AgentPolicyValidator.validateRequestCommands` | APPLY_AGENT_CONFIG / GET_AGENT_CONFIG / ENSURE_AGENT_CONFIG_ANNOTATIONS 누락 시 HIGH warning |

### Agent

| 컴포넌트 | 책임 |
|---------|------|
| `apply_config.go` | `helm_repositories` param 처리 + ConfigMap key write + `SyncRepositoriesWithCleanup` 호출 |
| `helm/repos.go` | `RepoEntry` + `ParseRepoList` + `SyncRepositories` (helm SDK RepositoryFile merge) |
| `helm.Client.Settings()` | RepositoryConfig path 접근 |
| `observability.go` | `repo` / `chart` cmd param 우선 — hardcoded 는 fallback |

### Orphan cleanup

`SyncRepositoriesWithCleanup` 은 `anycloud-*` prefix entry 만 orphan 으로 인식합니다. 사용자가 helm CLI 로
직접 추가한 entry 는 보존합니다. backend repo DELETE → ConfigMap 이 빈 array 가 되면 → agent RepositoryFile 의 orphan 이
자동 제거됩니다.

## 사용 시나리오

### 1. Default (out-of-the-box, public chart)

```yaml
# application.yaml — default 동작
helm-repo:
  auto-seed:
    enabled: true
    repos:
      - { name: prometheus-community, url: https://prometheus-community.github.io/helm-charts, tags: monitoring,seeded }
      - { name: grafana, url: https://grafana.github.io/helm-charts, tags: monitoring,seeded }
      - { name: bitnami, url: https://charts.bitnami.com/bitnami, tags: general,seeded }
```

backend 부팅 시 자동으로 등록됩니다 → admin apply-policy 호출 → agent 가 helm SDK 에 등록 →
`INSTALL_OBSERVABILITY_STACK` 이 `prometheus-community/kube-prometheus-stack` 을 자동으로 해결합니다.

### 2. Air-gapped (internal mirror 만)

```bash
# 1. external seed 비활성
export ANYCLOUD_HELM_REPO_AUTO_SEED=false

# 2. internal ChartMuseum 등록
curl -X POST .../v1/helm-repos -d '{
  "name": "chart-museum-internal",
  "url": "http://chartmuseum.internal:8080",
  "source": "INTERNAL",
  "tags": "mirror,mirrored-from:prometheus-community",
  "username": "...",
  "password": "..."
}'

# 3. observability install 시 repo override
ObservabilityStackInstaller.InstallRequest(
    "monitoring", "kube-prometheus-stack", "65.0.0", null,
    "chart-museum-internal",        // repo override
    "kube-prometheus-stack")
```

`application-docker.yaml` 의 `helm-repo.auto-seed.enabled=false` default — air-gapped 운영 자동 안전이며,
dev 만 external public seed 입니다.

### 3. Hybrid (둘 다 등록)

기본 seed + 추가 internal — 1) 그대로 + 2) 의 step 2 만 추가합니다. observability 는 default
`prometheus-community` 그대로 사용하고, 다른 자체 chart 만 internal 에서 사용합니다.

## 422 응답 recoveryHints

policy update 가 warning 으로 422 일 때 응답에 `recoveryHints` 필드가 포함됩니다 — warning code 별 kubectl patch /
보강 명령을 안내합니다.

## Limitations

- **Boot-time helm_repositories sync 없음** — 현재는 event-driven 만 동작합니다. backend 재기동 중 발생한
  변화는 다음 event 까지 누락 가능합니다. ACTIVE cluster 들에게 boot 직후 broadcast 는 미구현입니다.
- **agent test 부재** — `repos_test.go` 의 fake helm settings + RepositoryFile merge / orphan cleanup
  단위 테스트가 없습니다. 현재는 runtime 검증만 수행합니다.
- **broadcast retry 없음** — `broadcastHelmRepoChange` 의 per-cluster 실패 시 backoff retry 가 없습니다.
  1회 시도 + log only 입니다.

## 검증 명령

```bash
# (1) seed 결과
curl http://localhost:8888/v1/helm-repos | jq '.data.items[] | {name, source, tags}'
# → prometheus-community / grafana / bitnami 가 source=EXTERNAL tags=...,seeded

# (2) admin apply 트리거 (cluster-agent 에 push)
curl -X PUT http://localhost:8888/v1/admin/clusters/{cluster}/agent-policy \
  -d '{"allowedNamespaces":["*"], "allowedCharts":["*/*:0.0.0-99.99.99"], "allowedCommands":["*"]}'

# (3) agent ConfigMap 의 helm_repositories key 확인
kubectl get cm -n aipaas-system aipaas-agent-allowlist -o yaml | grep -A 30 helm_repositories

# (4) agent pod 의 helm SDK RepositoryFile 직접 확인 (debug)
kubectl exec -n aipaas-system <agent-pod> -- cat /root/.config/helm/repositories.yaml
# 또는 agent log
kubectl logs -n aipaas-system <agent-pod> | grep "helm repositories synced"
```
