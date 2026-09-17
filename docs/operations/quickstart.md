# Quickstart — backend 띄우고 test-console 로 검증

dev 환경에서 anycloud backend 를 5 분 안에 부팅해 `test-console` 페이지로
주요 시나리오 검증까지 도달하는 최단 경로입니다.

## 사전 요구

- JDK 21 (Temurin / GraalVM 등)
- Docker (compose v2)
- `openssl` (랜덤 키 생성용)

## Step 1 — dev infra (MariaDB + RabbitMQ)

```bash
cd <repo-root>
make dev-up
```

띄워지는 서비스:
- MariaDB `localhost:3306` (db=anycloud, user=anycloud)
- RabbitMQ `localhost:5672` (AMQP), `15672` (관리 UI)

확인합니다.
```bash
docker compose -f docker-compose.dev.yml ps
```

## Step 2 — 필수 환경 변수

backend 부팅 시 fail-fast 검증이 걸리는 키 두 개입니다. 미설정 시 부팅이 실패합니다.

```bash
export ANYCLOUD_AGENT_JWT_SECRET=$(openssl rand -hex 32)
export CSP_CREDENTIAL_ENCRYPTION_KEY=$(openssl rand -hex 32)

# DB 비밀번호 (compose 의 dev 기본값과 일치)
export DB_USER=anycloud
export DB_PASS=dev-only-pass-do-not-use-in-prod

# (선택) RabbitMQ workflow 까지 사용하려면 — compose 가 만드는 default 계정과 일치시킴.
# 안 주면 backend 가 guest/guest 로 시도하다 auth 실패 → workflow controller 만 비활성
# (다른 endpoint 는 정상). VM cluster workflow 검증 안 할 거면 생략 가능.
export RABBITMQ_USER=anycloud
export RABBITMQ_PASS=dev-only-pass-do-not-use-in-prod
export SPRING_RABBITMQ_USERNAME=$RABBITMQ_USER
export SPRING_RABBITMQ_PASSWORD=$RABBITMQ_PASS
export VM_CLUSTER_WORKFLOW_ENABLED=true
```

`.env` 파일로 관리하고 싶으면 `.env.sample` 을 복사 후 채웁니다. `.env` 는
`.gitignore` 에 이미 등재되어 있습니다.

## Step 3 — backend 기동

```bash
./gradlew :anycloud:bootRun
```

부팅 30~60 초 후 다음 로그가 보이면 성공입니다.
```
Tomcat started on port 8888 (http) with context path '/'
Started AnycloudApplication in X.X seconds
```

또는 host JVM 으로 직접 실행하려면 Makefile 의 dev launcher 를 사용합니다.
```bash
make dev-run           # 일반 모드 (의존 서비스는 dev-up 으로 띄운 상태 가정)
make dev-debug         # JDWP :5005 listen — IDE remote attach
```

## Step 4 — test-console 접속

브라우저:
- **테스트 페이지**: <http://localhost:8888/test-console/>
- Actuator health: <http://localhost:8888/actuator/health>

`security.auth.enabled=false` (default) 이므로 토큰 입력은 불필요합니다. 우상단 pill 이
"no token" 으로 표시되어 있어도 동작합니다.

## Step 5 — dev infra 만으로 검증 가능한 영역

| Tab | dev infra 만으로 동작? | 비고 |
|---|---|---|
| **Providers** | ✅ | CSP 메타데이터 (DB / 메모리) |
| **Credentials** | ✅ | MariaDB CRUD |
| **Validation** | △ | 외부 CSP API 호출이 없는 검증만 |
| **VM Clusters (목록)** | ✅ DB 부분만 | 프로비저닝은 Pulumi 필요 |
| **Agent** | ✅ 토큰 발급만 | 실제 agent Pod 등록은 K8s 환경 필요 |
| **Operations / Workflow / Audit Logs** | ✅ | DB / RabbitMQ |
| **K8s Resources / Helm / Observability** | ✗ | agent stream 또는 cluster kubeconfig 필요 |
| **Day-2** | ✗ | Pulumi + agent 필요 |

**최소 검증 흐름**:
1. Providers → "Providers 목록" → 응답에 aws/gcp/azure 등이 표시됩니다.
2. Credentials → 생성 (provider=aws + 임의 키) → 목록에 추가됩니다.
3. Workflow → "큐 상태" → RabbitMQ 큐 정보가 나옵니다.
4. Observability → preset dropdown 선택 → PromQL 이 자동 채워집니다 (실제 호출은 agent 가 필요합니다).

## 자주 만나는 부팅 실패

| 에러 메시지 | 원인 | 해결 |
|---|---|---|
| `DuplicateKeyException: anycloud` | `application.yaml` 중복 top-level | 이미 fix `f9712c8` 이후 발생 안 함 |
| `CSP_CREDENTIAL_ENCRYPTION_KEY blank / <32 chars` | 키 미설정 | `openssl rand -hex 32` |
| `cluster-agent.jwt.secret must be >= 32 bytes` | JWT 키 미설정 | `openssl rand -hex 32`. 미설정이어도 dev 는 random fallback (재시작마다 token 무효화 warning) |
| `Connection refused: 3306` | MariaDB 미기동 | `make dev-up` |
| `Connection refused: 5672` | RabbitMQ 미기동 | `make dev-up` |
| `change-me sentinel detected` | 토큰값이 알려진 sentinel 패턴 | 실제 random 값으로 교체 |
| `BIND port 8888 already in use` | 다른 프로세스가 사용 중 | `lsof -i :8888` 로 확인 후 종료 또는 `SERVER_PORT` env 로 변경 |
| `FlywayValidateException: Detected failed migration to version X` | 이전 부팅이 V_X 도중 실패해 schema history 에 failed row 잔존 | 아래 [Flyway 잔재 정리](#flyway-잔재-정리) 참고 |
| `No qualifying bean of type 'VmClusterWorkflowQueueService'` | RabbitMQ 접속 실패 (credentials mismatch) → service 비활성 | controller 가 `@ConditionalOnBean` 으로 skip 되어 더 이상 부팅 차단 안 함. workflow 사용하려면 `RABBITMQ_USER`/`PASS` env 설정 (Step 2 참고) |
| `Socket error / EOFException` 도중 migration | `dev-up` 직후 backend 가 너무 빨리 시도. MariaDB container 의 first-boot init 중에 server 가 한 번 restart 하면서 connection drop | `make dev-up` 이 이제 `--wait` 로 healthcheck 통과까지 기다림. 만약 같은 에러 재발 시 잠시 후 (10초) backend 재시작 |
| 코드 변경 후에도 같은 에러 — gradle log 에 `compileJava ... UP-TO-DATE` | incremental compile cache 가 source 변경 인지 못한 stale 상태 (폴더 재편 이후 가끔 관찰) | `./gradlew :anycloud:clean` 일회성 실행. |

### Flyway 잔재 정리

이전 dev iteration 의 부팅 실패가 `flyway_schema_history` 에 `success=0` row 를
남기면 다음 부팅이 막힙니다. 3가지 해법이 있습니다.

**(권장) dev profile 사용** — 매 부팅 시 자동 `flyway.repair()` 가 선행됩니다.
```bash
make dev-run                                 # SPRING_PROFILES_ACTIVE=dev 자동 설정
# 또는
SPRING_PROFILES_ACTIVE=dev ./gradlew :anycloud:bootRun
```
dev profile 에서만 `DevFlywayMigrationStrategy` bean 이 활성됩니다 — failed row 자동
삭제 후 migrate 합니다. 운영(default/prod) 에선 비활성입니다 (사람 손을 거쳐야 합니다).

**(빠른 dev 초기화) volume 통째 제거** — 잃을 데이터 없을 때 사용합니다.
```bash
make dev-reset
make dev-up
./gradlew :anycloud:bootRun
```

**(보존 + 수동 정리)** — DB 의 다른 데이터를 유지합니다.
```bash
docker exec -it anycloud-mariadb-dev mariadb -u anycloud -panycloud anycloud \
  -e "DELETE FROM flyway_schema_history WHERE success=0;"
./gradlew :anycloud:bootRun
```

## test-console 의 핵심 기능 요약

### Observability tab — PromQL 작업

- **Preset dropdown**: starter 의 표준 query 8개 카탈로그입니다. 선택 시 input 이 자동 채워집니다.
- **Range time-window 버튼**: "15m / 1h / 6h / 24h" — start/end/step 이 자동 계산됩니다.
- **history**: 최근 사용한 PromQL 5개입니다 (localStorage).
- **Auto-table**: vector / matrix / typed MetricSample 모두 자동 표 렌더입니다 — 우상단 [Table] [JSON] [copy] 토글이 있습니다.
- **matrix sparkline**: range query 결과는 ▁▂▃▄▅▆▇█ 로 추이를 표시합니다.

### Standard typed endpoint (PromQL 없이)

```
GET /v1/clusters/{cluster}/metrics/standard/node-cpu?window=5m
GET /v1/clusters/{cluster}/metrics/standard/node-memory
GET /v1/clusters/{cluster}/metrics/standard/namespace-cpu?window=5m
GET /v1/clusters/{cluster}/metrics/standard/namespace-memory
GET /v1/clusters/{cluster}/metrics/standard/pod-phases
GET /v1/clusters/{cluster}/metrics/standard/top-cpu?k=5&window=5m
```

응답은 `List<MetricSample>` 입니다 — labels / value / timestamp 가 포함됩니다.

### Authorization (선택)

`security.auth.enabled=true` 운영 환경입니다.
```bash
export SECURITY_AUTH_ENABLED=true
export SECURITY_AUTH_TOKEN=$(openssl rand -hex 32)
```

test-console 우상단 "Authorization" details 를 펼쳐서 토큰을 저장합니다.

## 첫 endpoint 따라가기 — 권장 코드 walkthrough

처음 온 사람이 backend 의 controller → service → entity 흐름을 한 번 trace 하면
이후 모든 도메인이 같은 패턴이라 빠르게 이해 가능. 권장 경로:

**가장 단순한 single-domain endpoint** — `GET /v1/audit-logs?since=&until=...`

```
파일 → 클래스 → 책임
1. apps/anycloud/.../domain/audit/web/AuditLogController.java     ← @RestController, request 검증
2. apps/anycloud/.../domain/audit/AuditLogService.java            ← interface (도메인 root)
3. apps/anycloud/.../domain/audit/internal/AuditLogServiceImpl.java  ← @Service, 비즈니스 로직
4. apps/anycloud/.../domain/audit/AuditLogRepository.java         ← Spring Data JPA
5. apps/anycloud/.../domain/audit/AuditLogEntity.java             ← @Entity (JPA)
6. apps/anycloud/.../domain/audit/model/AuditLog.java             ← immutable record (DTO 변환용)
```

IDE 의 "Go to Definition" 으로 1 hop 씩 따라가면 5분 안에 전체 layer 이해. 모든
14 domain 이 같은 구조 (`{domain}/web/`, `{domain}/internal/`, `{domain}/model/`,
`{domain}/api/{request,response}/`, `{domain}/mapper/`).

**그 다음**: 더 복잡한 흐름은 `GET /v1/clusters/{clusterName}` 추적:
- `cluster/web/ClusterController` → `cluster/ClusterFacade` (다중 sub-service 합성)
  → `cluster/internal/ClusterServiceImpl` → `cluster/mapper/ClusterMapper` (MapStruct)

**LRO (long-running operation)** 패턴은 `POST /v1/clusters` 에서 시작 → `operation/`
도메인 + `provisioning/workflow/` (RabbitMQ Saga) 가 reference.

## 다음 단계

- **Agent 연결**: K8s 클러스터에서 cluster-agent Pod 를 띄우고 Bootstrap.Register 를 호출합니다.
  자세한 흐름은 [`docs/architecture/cluster-agent.md`](../architecture/cluster-agent.md) 를 참고합니다.
- **Pulumi**: VM provisioning 활성화입니다. [`docs/architecture/pulumi/pulumi-runtime-with-gateway.md`](../architecture/pulumi/pulumi-runtime-with-gateway.md) 를 참고합니다.
- **K8s 접근 path**: agent path 와 fabric8 fallback 입니다. [`docs/architecture/k8s-access-paths.md`](../architecture/k8s-access-paths.md) 를 참고합니다.

## clean up

```bash
make dev-down     # volume 보존
make dev-reset    # volume 까지 제거 (완전 초기화)
```
