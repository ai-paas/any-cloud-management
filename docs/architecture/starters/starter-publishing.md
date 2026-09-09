# Starter Publishing 가이드

3 starter (`cluster-agent`, `cluster-agent-features`, `cluster-provisioning`) 의 외부 Maven
consumer 활용 + publish 운영 안내입니다.

## 1. 좌표

모두 `group = io.aipaas.cluster` 입니다.

| Artifact                                       | Version          | Status                              |
| ---------------------------------------------- | ---------------- | ----------------------------------- |
| `cluster-agent-spring-boot-starter`            | `0.1.0`          | release-ready (Layer 1)             |
| `cluster-agent-features-spring-boot-starter`   | `0.1.0`          | release-ready (Layer 2 — RBAC + Backup + Observability 통합) |
| `cluster-provisioning-spring-boot-starter`     | `0.1.0-SNAPSHOT` | scaffold — service migration 후 0.1.0. cluster-agent 와 별개 lifecycle. |

## 2. 외부 consumer (Maven Local)

### 2.1 Publish to local

```bash
# 3 starter 모두 ~/.m2/repository/io/aipaas/cluster/ 에 publish.
./gradlew \
  :cluster-agent-spring-boot-starter:publishToMavenLocal \
  :cluster-agent-features-spring-boot-starter:publishToMavenLocal \
  :cluster-provisioning-spring-boot-starter:publishToMavenLocal
```

산출물은 각 artifact 마다 4 file 입니다.
- `cluster-agent-spring-boot-starter-0.1.0.jar` — 클래스 + 리소스 + proto
- `cluster-agent-spring-boot-starter-0.1.0-sources.jar` — sources
- `cluster-agent-spring-boot-starter-0.1.0-javadoc.jar` — javadoc
- `cluster-agent-spring-boot-starter-0.1.0.pom` — POM 입니다 (의존성 + license + SCM).

### 2.2 Consumer build.gradle

```groovy
repositories {
    mavenLocal()        // 본 starter 가 publishToMavenLocal 로 배포되어 있다고 가정
    mavenCentral()
}

dependencies {
    // Layer 1 — 모든 다른 starter 가 의존하는 transport baseline
    implementation 'io.aipaas.cluster:cluster-agent-spring-boot-starter:0.1.0'

    // Layer 2 통합 — RBAC + Backup + Observability (3 sub-feature 한 artifact)
    implementation 'io.aipaas.cluster:cluster-agent-features-spring-boot-starter:0.1.0'

    // 별개 lifecycle — Pulumi CLI orchestration (cluster-agent 의존 X)
    implementation 'io.aipaas.cluster:cluster-provisioning-spring-boot-starter:0.1.0-SNAPSHOT'
}
```

### 2.3 Host backend 가 구현해야 할 SPI

| Starter                     | 필수 SPI                                                                |
| --------------------------- | ----------------------------------------------------------------------- |
| `cluster-agent`             | `AgentIdentityStore`, `IdempotencyStore` (default in-memory — production 권장 X) |
| `cluster-agent` (선택)       | `ImpersonationContext` (default ThreadLocalImpersonationContext + WebMVC interceptor) |
| `cluster-observability`     | `ClusterCatalog` (cluster 목록)                                          |
| `cluster-backup`         | `BackupHistoryWriter` (default NoOp — production 권장 X)                |
| `cluster-provisioning` (scaffold) | `ClusterProvisioningSink`, `PulumiCredentialProvider` (service migration 완료 시 활성화) |

자세한 사항은 각 starter 의 design doc 을 참조합니다.
- `docs/architecture/cluster-agent.md`
- `docs/architecture/starters/cluster-backup-starter.md`
- `docs/architecture/starters/cluster-provisioning-starter.md`
- `docs/architecture/identity/k8s-impersonation-auth.md` (Impersonation SPI) 입니다.

### 2.4 Feature toggle

원치 않는 feature 는 application.yml 로 disable 합니다.

```yaml
cluster-agent:
  exec:
    enabled: false             # PodExec WebSocket 비활성 (gRPC API 만)

cluster-backup:
  upgrade:
    enabled: false             # K8s upgrade service 미사용
  backup:
    enabled: false             # etcd/PKI backup 미사용
  velero:
    enabled: false             # Velero install/Backup/Restore/Schedule 미사용

cluster-observability:
  alerts:
    enabled: false             # PrometheusRule catalog/installer 미사용
  dashboards:
    enabled: false             # Grafana dashboard import 미사용

cluster-provisioning:
  enabled: false               # scaffold 전체 비활성
```

## 3. Remote publish (Nexus / Artifactory / GitHub Packages)

`gradle.properties` (또는 `~/.gradle/gradle.properties`, 또는 CI env) 에 다음과 같이 정의합니다.

```
publishUrl=https://nexus.example.com/repository/maven-releases/
publishUsername=ci-bot
publishPassword=<token>
```

```bash
./gradlew \
  :cluster-agent-spring-boot-starter:publish \
  :cluster-agent-features-spring-boot-starter:publish \
  :cluster-provisioning-spring-boot-starter:publish
```

`publishUrl` 미정의 시 remote repository task 가 등록되지 않아 누락 실수를 방지합니다.

## 4. Maven Central — 추가 준비

각 starter 의 POM 은 license / SCM / developers 가 완비되어 있습니다. Maven Central publish 추가 요구사항은 다음과 같습니다.
1. **GPG signing** — `signing` plugin + key pair
2. **Sonatype OSS 계정** — `central-publishing-maven-plugin` 또는 `nexus-publish` 플러그인 입니다.
3. **Domain 소유 검증** — `io.aipaas.cluster` 도메인 (aipaas.io? innogrid.com?) DNS TXT 또는 GitHub repo 검증입니다.

Maven Central 정식 release 도입은 향후 결정합니다 (현재 publishToMavenLocal 검증 단계입니다).

## 5. SemVer 정책

- **MAJOR** — SPI breaking, proto wire-format breaking
- **MINOR** — 새 SPI 추가, 새 proto field 추가 (backward-compatible), 새 CommandType 입니다.
- **PATCH** — bug fix, internal 개선

현재 `0.1.0` 은 impersonation 통합 + cluster_addon async workflow + 4-starter split 안정화 시점입니다.

## 6. 검증

```bash
# publish 후 ~/.m2 에 jar/pom 모두 생성되는지 확인
ls ~/.m2/repository/io/aipaas/cluster/cluster-agent-spring-boot-starter/0.1.0/
# cluster-agent-spring-boot-starter-0.1.0.jar
# cluster-agent-spring-boot-starter-0.1.0-sources.jar
# cluster-agent-spring-boot-starter-0.1.0-javadoc.jar
# cluster-agent-spring-boot-starter-0.1.0.pom

# 다른 sample Spring Boot project 에서 build.gradle 에 add 후 build:
gradle dependencies | grep io.aipaas.cluster
```
