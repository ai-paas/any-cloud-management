# cluster-agent Observability sub-feature

각 Kubernetes cluster 안의 Prometheus / Alertmanager / Grafana 에 대한 PromQL passthrough, alert
rule 설치, dashboard 위치 조회. Layer 2 통합 starter
[`cluster-agent-features-spring-boot-starter`](./cluster-agent-features-starter.md) 의
sub-package `io.aipaas.cluster.agent.observability` 으로 제공. Layer 1
`cluster-agent-spring-boot-starter` 의 reverse-tunnel 인프라를 활용해 cluster API server 를 외부에
노출하지 않고도 모니터링 데이터에 접근합니다.

## 구성 (Architecture)

본 starter 는 `io.aipaas.cluster.agent.observability` 하위 7 패키지로 구성됩니다.

| 패키지 | 핵심 component | 역할 |
|---|---|---|
| `core` | `ClusterCatalog`, `ClusterCapabilitiesSink`, `DashboardSource`, `PromQLResult`, `AlertsResult`, `AlertSilenceResult`, `MetricTargetsResult`, `DashboardLocation`, `ClusterCapabilities`, `ObservabilityException` | SPI + 결과 record + 예외 |
| `query` | `ObservabilityQueryService` | PromQL instant / range / multi-cluster fan-out, targets, alerts, silences |
| `metrics` | `ClusterMetricsService`, `StandardQueries`, `StandardQuery`, `MetricsResultParser`, `MetricSample` | 표준 메트릭 (노드/네임스페이스/pod) 헬퍼 |
| `alerts` | `AlertRuleCatalog`, `AlertRuleInstaller`, `AlertRuleSet`, `AlertRuleApplyResult` | anycloud-default PrometheusRule 카탈로그 + cluster install/uninstall |
| `dashboard` | `DashboardLocator` | Grafana 외부 접근 URL 조회 |
| `stack` | `DefaultDashboardImporter`, `HelmReleaseLookup`, `GpuCapabilityHeartbeatListener` | Grafana dashboard import, kube-prometheus-stack 존재 확인, heartbeat → capability backfill |
| `autoconfigure` | `ObservabilityAutoConfiguration`, `ObservabilityProperties` | bean 자동 등록 + `cluster-observability` prefix 설정 |

### 데이터 흐름

```
Frontend
   |  HTTP(S)
   v
호스트 REST controller
   |  ObservabilityQueryService.queryInstant(...)
   v
ObservabilityQueryService (이 starter)
   |  AgentSessionRegistry.sendCommand(QUERY_METRICS, ...)
   v
cluster-agent-spring-boot-starter (gRPC)
   |  ControlMessage 를 reverse-tunnel 로 전송
   v
cluster-agent (Go, in-cluster)
   |  HTTP GET
   v
in-cluster Prometheus / Alertmanager / Grafana
```

본 starter 자체는 HTTP endpoint 를 노출하지 않으며, service bean 만 제공합니다. REST controller 는 호스트가
작성합니다.

## 의존성 (Dependencies)

| 영역 | 좌표 |
|---|---|
| 선행 starter | `api project(':cluster-agent-spring-boot-starter')` |
| Spring Boot | `spring-boot-starter:3.5.16`, `spring-boot-starter-validation:3.5.16` |
| Jackson | `cluster-agent` 로부터 transitive |
| 빌드 보조 | `spring-boot-configuration-processor` (annotationProcessor), Lombok (compileOnly) |

cluster-agent starter 는 transitive 로 따라오므로 별도로 추가할 필요가 없으나, agent starter 의 SPI
(`AgentIdentityStore`, `IdempotencyStore`) 와 JWT secret 은 본 starter 도 동일하게 요구합니다.

`group = io.aipaas.cluster`, `version = 0.1.0`. 다른 Layer-2 starter
(`cluster-agent-backup-spring-boot-starter`) 와 동일한 publish pattern 을 사용합니다.

## AutoConfiguration

`ObservabilityAutoConfiguration` 의 활성화 조건과 등록 bean 은 다음과 같습니다.

### 활성 조건

- `@ConditionalOnClass(ObservabilityQueryService.class)` — classpath 확인.
- `@ConditionalOnBean(ClusterCatalog.class)` — 호스트가 `ClusterCatalog` 구현 bean 을 제공해야 활성됩니다.
  default 구현은 제공되지 않습니다.

### 등록 bean (모두 `@ConditionalOnMissingBean`)

- `ObservabilityQueryService` — PromQL 쿼리 / targets / alerts / silences API
- `ClusterMetricsService` — 표준 메트릭 헬퍼
- `AlertRuleCatalog`, `AlertRuleInstaller` — `cluster-observability.alerts.enabled=false` 시 일괄 비활성
- `DashboardLocator` — Grafana URL 조회
- `HelmReleaseLookup` — kube-prometheus-stack release 확인
- `DefaultDashboardImporter` — `cluster-observability.dashboards.enabled=false` 시 비활성
- `GpuCapabilityHeartbeatListener` — `ClusterCapabilitiesSink` bean 이 존재할 때만 등록
  (`@ConditionalOnBean(ClusterCapabilitiesSink.class)`)

`HelmReleaseLookup` 과 `DefaultDashboardImporter` 는 cluster-agent starter 의 `AgentSessionRegistry` 를
직접 inject 받아 명령을 호출합니다.

### Properties — `cluster-observability.*`

`ObservabilityProperties` 의 default 는 compact constructor 에서 채워집니다.

| Key | Type | Default | 설명 |
|---|---|---|---|
| `cluster-observability.query.default-timeout` | `Duration` | `5s` | PromQL / targets / alerts agent 호출 timeout |
| `cluster-observability.query.fan-out-timeout` | `Duration` | `8s` | multi-cluster `queryAll` 의 per-cluster timeout |
| `cluster-observability.install.timeout` | `Duration` | `10m` | kube-prometheus-stack helm install 대기 |
| `cluster-observability.dashboard.timeout` | `Duration` | `3s` | `GET_DASHBOARD_URL` 호출 timeout |
| `cluster-observability.auto-install.enabled` | `boolean` | `true` | agent ACTIVE 전환 시 자동 install |
| `cluster-observability.auto-install.release-lookup-timeout` | `Duration` | `5s` | install 전 `LIST_HELM_RELEASES` 확인 |
| `cluster-observability.auto-install.default-alert-rules` | `boolean` | `true` | stack 설치 직후 default PrometheusRule 일괄 install |
| `cluster-observability.alerts.enabled` | `boolean` | `true` (matchIfMissing) | alert rule catalog / installer kill-switch |
| `cluster-observability.dashboards.enabled` | `boolean` | `true` (matchIfMissing) | `DefaultDashboardImporter` kill-switch |

## 사용

### Gradle dependency

```gradle
implementation project(':cluster-agent-observability-spring-boot-starter')
// cluster-agent starter 는 transitive — 별도 추가 불필요
```

### application.yml

```yaml
cluster-observability:
  query:
    default-timeout: 5s
    fan-out-timeout: 8s
  install:
    timeout: 10m
  dashboard:
    timeout: 3s
  alerts:
    enabled: true
  dashboards:
    enabled: true
```

cluster-agent starter 의 설정 (JWT secret, `AgentIdentityStore` 구현) 도 함께 필요합니다.

### Bean inject + 호출

```java
@Component
@RequiredArgsConstructor
class AnycloudClusterCatalog implements ClusterCatalog {
    private final ClusterRepository repo;
    public List<String> listClusterNames() {
        return repo.findAllByStatus(ClusterStatus.ACTIVE).stream()
                .map(ClusterEntity::getId).toList();
    }
}

@RestController
@RequiredArgsConstructor
class ObservabilityController {
    private final ObservabilityQueryService query;
    private final ClusterMetricsService metrics;
    private final DashboardLocator dashboardLocator;
    private final AlertRuleInstaller alertRuleInstaller;

    @GetMapping("/v1/clusters/{c}/metrics/query")
    public PromQLResult q(@PathVariable String c, @RequestParam String promql) {
        return query.queryInstant(c, promql, null, null);
    }

    @GetMapping("/v1/observability/aggregate")
    public Map<String, PromQLResult> fan(@RequestParam String promql) {
        return query.queryAll(promql, Duration.ofSeconds(8));
    }

    @GetMapping("/v1/clusters/{c}/nodes/cpu")
    public List<MetricSample> nodeCpu(@PathVariable String c) {
        return metrics.nodeCpuUsage(c, Duration.ofMinutes(5), null);
    }

    @GetMapping("/v1/clusters/{c}/observability/dashboard")
    public DashboardLocation dash(@PathVariable String c) {
        return dashboardLocator.locate(c, null, null);
    }

    @PostMapping("/v1/clusters/{c}/alert-rules/{ruleSet}")
    public AlertRuleApplyResult installRules(@PathVariable String c, @PathVariable String ruleSet) {
        return alertRuleInstaller.install(c, ruleSet, Duration.ofSeconds(15));
    }
}
```

### Cluster-agent AllowList 등록

본 starter 가 호출하는 명령은 cluster-agent 의 AllowList 를 통과해야 합니다.

```yaml
data:
  allowed_commands: |
    - INSTALL_OBSERVABILITY_STACK
    - QUERY_METRICS
    - LIST_METRIC_TARGETS
    - LIST_ALERTS
    - GET_DASHBOARD_URL
  allowed_namespaces:
    - monitoring
  allowed_charts:
    - prometheus-community/kube-prometheus-stack:60.0.0-70.0.0
```

## 한계 / 확장 점

- **ClusterCatalog 필수** — bean 이 없으면 auto-config 자체가 비활성됩니다. 호스트가 cluster 식별자
  목록 source (DB / 정적 list / 외부 API) 를 반드시 제공해야 합니다.
- **REST controller 미제공** — starter 는 service bean 만 제공합니다. controller 는 호스트가 직접
  작성합니다 (cluster-backup starter 와 동일한 pattern).
- **HTTP client 미사용** — Prometheus / Alertmanager 와의 실제 HTTP 통신은 in-cluster Go agent 가 수행
  합니다. 본 starter 는 agent 명령을 wrap 한 후 결과 JSON 을 `PromQLResult.raw` 등으로 forward 합니다.
- **AlertRuleCatalog 의 분류 기준** — 현재 classpath 의 `alert-rules/*.yaml` 을 그대로 로드합니다.
  분류 / 태깅 / 필터링 같은 고급 메타데이터는 미지원입니다.
- **Stack 자동 설치의 trigger** — agent ACTIVE 전환 시 자동 install 은 호스트의 addon orchestrator
  (예: anycloud `MonitoringAddonInstaller`) 가 호출하는 패턴입니다. starter 는 helper bean
  (`HelmReleaseLookup`, `DefaultDashboardImporter`) 과 properties 만 제공합니다.

연관 문서: [`docs/architecture/monitoring-discovery.md`](../monitoring-discovery.md),
[`docs/architecture/cluster-agent.md`](../cluster-agent.md),
[`docs/architecture/starters/cluster-agent-starter.md`](cluster-agent-starter.md).
