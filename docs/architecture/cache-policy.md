# Caffeine Cache Policy

anycloud backend 의 in-process cache 전략 정리. 모든 cache 는 Caffeine bounded —
`expireAfterWrite` 또는 `expireAfterAccess` + `maximumSize` 로 OOM 차단. 외부 cache
(Redis 등) 미사용 — single-instance backend 전제이고 cache miss penalty 가 작아 분산 cache
overkill.

## 1. Cache 일람표

| 위치 | 키 → 값 | TTL | Max size | Eviction | 목적 |
|---|---|---|---|---|---|
| **CacheConfig.vmOptions.regions** | `(provider, credentialId)` → `List<VmOptionRegion>` | 30m write | 1,000 (전 cache 합산) | TTL + LRU | CSP API quota 절약 (AWS describeRegions 등) |
| **CacheConfig.vmOptions.specs** | `(provider, credentialId, region, keyword, gpuOnly, limit)` → `List<VmOptionSpec>` | 30m write | 1,000 (전 cache 합산) | TTL + LRU | CSP spec catalog — 분/시간 단위로만 변동 |
| **CacheConfig.vmOptions.images** | `(provider, credentialId, region, keyword, architecture, owner, limit)` → `List<VmOptionImage>` | 30m write | 1,000 (전 cache 합산) | TTL + LRU | CSP image catalog — 안정적 |
| **CacheConfig.helm.chartValues** | chart coord → values.yaml | 30m write | 1,000 (전 cache 합산) | TTL + LRU | Helm chart download 비용 절약 |
| **CacheConfig.helm.chartReadme** | chart coord → README.md | 30m write | 1,000 (전 cache 합산) | TTL + LRU | Helm chart download 비용 절약 |
| **BootstrapRateLimitFilter.counts** | client IP → 1분 누적 카운터 | 1m write | 10,000 | TTL + LRU | rate limit window — Caffeine 이 window 만료를 자동 처리 |
| **AgentBootstrapKubeClient.cache** | cluster ID → KubernetesClient | 10m **access** | 50 | LRU + 미사용 idle | bootstrap-only K8s client — eviction 시 자동 close |
| **CachedKindResolver.cache** | (cluster, GVK) → kind metadata | 30m write | 5,000 | TTL + LRU | K8s schema/CRD discovery 결과 — schema 거의 불변 |
| **SimpleBindingFleetView.perCluster** | cluster → fleet view rows | 30s write (`fleetView.cacheTtl`) | 1,000 | TTL + LRU | RBAC fleet view — agent gRPC fan-out 대신 cache |
| **AgentSessionRegistry.pendingByRequest** | request ID → PendingCommand | 5m write | 10,000 | TTL + LRU | in-flight gRPC command tracker — stuck agent OOM 방지 |

## 2. 정책 원칙

### TTL 선택 가이드
- **30분 (write-TTL)**: read-mostly 외부 데이터. CSP catalog, Helm chart, K8s schema 등. staleness OK.
- **10분 (access-TTL)**: 자원 보유 cache (K8s client) — 사용 중이면 유지, 미사용 시만 release.
- **5분 (write-TTL)**: in-flight request tracking. stuck operation cleanup.
- **30초~1분 (write-TTL)**: hot path 정책 (rate limit window, fleet view) — staleness 민감.

### maxSize 선택 가이드
- **50**: 자원 무거운 cache (K8s client). bootstrap-only 라 cluster 수 가정.
- **1,000**: 일반 catalog/view. provider × region × parameter 조합.
- **5,000**: K8s schema (cluster × kind) cross-product 여유.
- **10,000**: hostile traffic 보호 (rate limit, in-flight). bounded scanner / stuck agent.

### Eviction 자원 release
- `AgentBootstrapKubeClient` 의 K8s client 는 `RemovalListener` 가 `client.close()` 호출 — eviction 시 자동 정리. **다른 cache 의 value 가 close-able 자원이면 동일 패턴 필요**.

## 3. recordStats + Micrometer

`CacheConfig` 의 Caffeine builder 는 `.recordStats()` 호출 — Spring Boot Actuator 가 자동으로
`cache.*` metric 노출:

| Metric | 의미 |
|---|---|
| `cache.gets{result="hit"}` | hit count |
| `cache.gets{result="miss"}` | miss count |
| `cache.puts` | put count |
| `cache.evictions` | TTL/LRU 로 제거된 entry |
| `cache.size` | 현재 entry 수 |

manual cache (`Caffeine.newBuilder()` 직접 호출) 는 `.recordStats()` 명시 시 동일 노출 가능 —
필요 시 추가. 현재는 Spring abstraction cache 만 모니터링.

### Alert 임계 권장
| Metric | Threshold | Alert |
|---|---|---|
| `cache.gets{cache="vmOptions.*", result="miss"}` rate | > 1/s 지속 | warning — CSP API rate-limit 위험 |
| `cache.size{cache="helm.chartValues"}` | > 800 | warning — maxSize 임박 |
| `pending.size` (agent registry) | > 5,000 | warning — stuck agent 다수 |

## 4. invalidation 정책

| Cache | 변경 detection | invalidation 방식 |
|---|---|---|
| `vmOptions.*` | CSP catalog 갱신 | TTL 자연 만료 (30m). manual evict 필요 시 `CacheManager.getCache().clear()` |
| `helm.*` | repo index 갱신 | TTL 자연 만료. helm repo refresh API 사용 시 함께 evict 권장 |
| `kindResolver` | CRD 추가/삭제 | TTL 자연 만료 (30m). 즉시 반영 필요 시 cluster 별 evict |
| `agentBootstrapKube` | cluster kubeconfig 변경 | 명시적 evict 필요 — 현재는 idle 10분 후 자동 release |
| `bindingFleetView` | RBAC binding 변경 | TTL 자연 만료 (30s) — UI refresh 시 사실상 즉시 반영 |
| `pendingByRequest` | command complete/timeout | 명시적 remove (성공 시) + TTL fallback (stuck 시) |

## 5. anti-pattern (회피)

| 패턴 | 문제 |
|---|---|
| `Caffeine.newBuilder().build()` (unbounded) | OOM risk — maxSize 또는 weigher 필수 |
| TTL 없이 maxSize 만 | 캐시된 데이터가 영구 stale 가능 |
| 자원 보유 cache 에 RemovalListener 없음 | eviction 시 자원 leak (K8s client / DB connection 등) |
| `@Cacheable` 에 mutable key (List, Map) | hash 불일치 → 동일 query 가 다른 entry 됨 |
| 외부 시스템 의존 cache 에 짧은 TTL | API rate-limit 초과 위험 |

## 6. 분산 cache 미도입 사유

현재 single-instance backend 전제. multi-instance scale-out 시 다음 검토:

| 분산 cache 옵션 | 장점 | 단점 |
|---|---|---|
| Redis | 일반화, TTL 지원, pub/sub invalidation | 운영 burden, network round-trip |
| Hazelcast IMap | 동일 JVM 통합, near-cache | configuration 복잡 |
| 분산 미도입 (현재) | zero overhead | instance 간 cache miss 불일치 |

**의사결정 트리거**: backend instance ≥ 3, 또는 CSP API rate-limit fail 빈도 증가.

## 7. ConcurrentMap 사용 site (Caffeine 미사용)

다음은 Caffeine bounded cache 가 아닌 `ConcurrentHashMap` 사용 — eviction 정책 없음:

- `ClusterPolicyBootstrapper` — cluster bootstrap state (in-memory FSM)
- `DriftDetector.lastSeenState` — drift detection baseline
- 각종 component 의 `volatile reference` 상태

→ 이들은 **state holder** 이지 cache 가 아님. bounded growth 가 guaranteed (cluster 수 cap) 이거나
명시적 cleanup 경로 보유. cache 정책 적용 대상 아님.
