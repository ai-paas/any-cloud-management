# spring-boot-host — 3 starter 로컬 사용 예제

`cluster-*-spring-boot-starter` 3개를 외부 프로젝트에서 가져와 사용하는 minimal Spring Boot 호스트
입니다. 본 example 은 monorepo 와 분리된 standalone Gradle project 입니다.

## 검증 절차

### 1. monorepo 에서 3 starter 를 mavenLocal 에 게시합니다.

```bash
# 상위 anycloud monorepo 디렉토리에서 실행합니다.
./gradlew \
  :cluster-agent-spring-boot-starter:publishToMavenLocal \
  :cluster-agent-features-spring-boot-starter:publishToMavenLocal \
  :cluster-provisioning-spring-boot-starter:publishToMavenLocal
```

게시 후 `~/.m2/repository/io/aipaas/cluster/` 에 3 starter 의 jar 가 위치합니다 (version
`0.1.0-SNAPSHOT` — release tag push 시 publish-starters.yml workflow 가 `-PstarterVersion=X.Y.Z`
주입).

### 2. example app 을 빌드 + 실행합니다.

본 example 은 자체 gradle wrapper 가 없습니다. monorepo 의 wrapper 를 사용하세요:

```bash
cd examples/spring-boot-host
../../gradlew build              # 의존성 resolve + compile + test 검증
../../gradlew bootRun            # 실 부팅 (실 cluster 연결 X — wiring 검증용)
```

또는 jar 만 가져갈 경우:

```bash
ls ~/.m2/repository/io/aipaas/cluster/cluster-agent-spring-boot-starter/0.1.0-SNAPSHOT/
# cluster-agent-spring-boot-starter-0.1.0-SNAPSHOT.jar 를 외부 프로젝트의 libs/ 에 복사하여 사용합니다.
```

### 3. wiring 확인 endpoint 를 호출합니다.

```bash
curl http://localhost:8080/info
```

응답 예시:

```json
{
  "cluster-agent-starter": "wired",
  "cluster-agent-features-starter": "host SPI 미제공 (rbac/backup/observability 모두 비활성)",
  "cluster-provisioning-starter": "host SPI 미제공"
}
```

`cluster-agent-starter` 는 host SPI 가 필요 없는 core bean (`AgentSessionRegistry` 등) 을 제공하므로
바로 wiring. `cluster-agent-features-starter` 의 sub-feature (rbac/backup/observability) 는 host
SPI (예: `BindingApplyClient`, `BackupHistoryWriter`, `ClusterCatalog`) 구현 시에만 활성.
`cluster-provisioning-starter` 도 host 가 5 SPI (ProcessExecutor, PulumiExecutionConfig,
ClusterDescriptor, ClusterDescriptorRepository, PulumiBackupPropertiesProvider) 구현 시 활성.

## 3 starter 의 host 측 책임

| Starter | host 가 구현해야 하는 SPI | 주요 properties |
|---|---|---|
| `cluster-agent` | (선택) `AgentIdentityStore`, `IdempotencyStore` (없으면 InMemory default) | `anycloud.cluster-agent.jwt.secret` |
| `cluster-agent-features` | sub-feature 별: `BindingApplyClient` (rbac), `BackupHistoryWriter` (backup, NoOp default), `ClusterCatalog` (observability) | 각 sub-feature properties |
| `cluster-provisioning` | `ProcessExecutor` (default 제공), `PulumiExecutionConfig` (default 제공), `ClusterDescriptor`/`ClusterDescriptorRepository` (필수), `PulumiBackupPropertiesProvider` (default 비활성) | `cluster-provisioning.pulumi.*` |

자세한 SPI 와 properties 는 각 starter 의 README + `docs/architecture/starters/` 를 참고합니다.

## 프로젝트 구조

```
examples/spring-boot-host/
├── build.gradle              # 3 starter dependency + mavenLocal 사용 선언
├── settings.gradle           # standalone Gradle project
├── README.md                 # 본 문서
└── src/main/
    ├── java/com/example/host/
    │   ├── HostApplication.java    # @SpringBootApplication entry
    │   └── HostController.java     # /info wiring 확인 endpoint
    └── resources/
        └── application.yml         # 3 starter properties 예시
```

## 한계

- 본 example 은 wiring 검증 전용입니다 — 실제 cluster 연결은 host 측 SPI 구현 후에 가능합니다.
- 3 starter 모두 `0.1.0-SNAPSHOT` 좌표 (publishToMavenLocal default). release tag (v*.*.*) push 시
  workflow 가 `-PstarterVersion` 주입으로 정식 좌표 (예: `0.1.0`) 게시.
- 외부 게시 경로: GHCR Maven (release tag 시 publish-starters.yml workflow 가 자동) 또는 로컬
  build → mavenLocal → consumer fetch.
