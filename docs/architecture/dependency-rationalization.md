# Dependency Rationalization

anycloud 가 의존하는 외부 broker / storage 는 **MariaDB + RabbitMQ** 2개입니다.

| Dependency | 책임 |
|---|---|
| **MariaDB** | 영구 저장소 (`cluster` / `cluster_agent` / `operation` / `vm_cluster` / `bootstrap_jti_used`) |
| **RabbitMQ** | VM cluster workflow 메시지 버스 (provisioning state machine) |

## JWT JTI 중복 방지 — MariaDB 패턴

`bootstrap_jti_used` 테이블 + `INSERT IGNORE` 로 1회 사용을 강제합니다.

- Flyway `V12__bootstrap_jti_used.sql` — `(jti PK, used_at, expires_at, INDEX(expires_at))` 입니다.
- `JpaIdempotencyStore` — `saveAndFlush` + `DataIntegrityViolationException` 을 catch 합니다.
  `@Transactional(propagation = REQUIRES_NEW)` 입니다.
- `BootstrapJtiCleanupSweeper` — 매일 04:00 ShedLock 보호하에 만료 jti 를 일배치 삭제합니다.

jti 부하는 분당 발급 토큰 수 × TTL 분 ≈ 0~100 행으로 가볍습니다.

## Agent registration 이벤트

proto `events.proto` 는 messaging-agnostic schema 로 유지합니다 — `ClusterInstallCompleted`,
`ClusterRegistered`, `ClusterStatusChanged`, `ClusterInternalEvent` 등이 있습니다. 현재 publisher 는 부재합니다.
필요 시점에 RabbitMQ topic exchange (`cluster.registration.*` / `cluster.lifecycle.*` /
`cluster.events.*` routing key) 를 도입하며 — schema 를 재사용합니다.

## Docker compose

`docker-compose.dev.yml` 은 **MariaDB + RabbitMQ** 로 구성됩니다.
- 메모리 사용량은 ~0.5 GB
- `make dev-up` 으로 부팅합니다.

## 통합 테스트

- `application-test.yaml` — MariaDB + RabbitMQ 만 active
- Testcontainers 가 두 컴포넌트를 spin up 합니다.

## 현재 Spring Boot starter 표면

| Starter | 책임 | 비고 |
|---|---|---|
| `web` | REST API | 필수 |
| `actuator` | health / metrics | 필수 |
| `security` | auth | auth toggle 옵션 |
| `data-jpa` | ORM | 필수 |
| `validation` | DTO 검증 | 필수 |
| `amqp` | RabbitMQ | workflow 핵심 |
| `websocket` | PodExec | starter transitive |
