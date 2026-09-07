# Gateway 뒤에서 Spring + Pulumi 운영 구조

`any-cloud-management` 는 외부에서 직접 Pulumi 를 호출하지 않고, Gateway 를 통해 들어온 요청을
Spring Boot 가 받아 내부에서 Pulumi 를 실행합니다.

구조는 다음과 같습니다.

```text
Client
  -> API Gateway
  -> anycloud-backend (Spring Boot)
  -> Pulumi Automation API (생성된 YAML 프로그램 실행)
  -> AWS / GCP / Azure / OpenStack ...
```

핵심 포인트는 다음과 같습니다.

- Pulumi program 은 별도 프로세스가 아니라 backend JVM 안에서 실행됩니다.
- provider 별 구현은 `libs/cluster-provisioning-spring-boot-starter` 의 `*Provisioner` 입니다.
- Automation API 가 up / destroy / stack output 을 담당합니다.
- 긴 작업은 HTTP 요청 안에서 직접 처리하지 않고 비동기 Job 으로 돌립니다.

## 실행 모델

### 1. API 요청

사용자는 Gateway 를 통해 Spring API 를 호출합니다.

예시는 다음과 같습니다.

- `POST /v1/clusters` (body `{source:"vm", ...}`) → 202 + Operation
- `DELETE /v1/clusters/{name}` → 202 + Operation
- 진행 추적: `GET /v1/operations/{operationId}` (polling) 또는
  `GET /v1/operations/{operationId}/events` (SSE)

전체 v1 API surface 는 [v1-reference.md](../../api/v1-reference.md) 를 참고합니다.

### 2. Spring 에서 Job 등록

Spring 은 요청 직후 다음을 수행합니다.

1. 메타데이터 DB 에 `PROVISIONING` 상태를 저장한다
2. Pulumi stack 이름을 만든다
3. 비동기 Executor 또는 Job Queue 에 실행을 넘긴다
4. 즉시 `202 Accepted` 를 반환한다

### 3. 비동기 Worker 에서 Pulumi 실행

Worker 는 다음 순서로 처리합니다.

1. `LocalWorkspace.createOrSelectStack()` 으로 stack 을 준비한다
2. CSP credential 을 Pulumi config 로 주입한다
3. `stack.up()` 을 호출한다
4. `stack.outputs()` 로 결과를 읽는다
5. output 을 DB 에 저장한다
6. 상태를 `READY` 또는 `FAILED` 로 바꾼다

CLI 를 프로세스로 부르지 않고 Automation API 로 같은 일을 합니다.

## Docker 구성 전략

처음 단계에서는 다음 두 가지 중 하나를 택하면 됩니다.

### 추천: Spring 컨테이너 안에 Pulumi 실행 환경 포함

`anycloud-backend` 이미지 안에 아래를 함께 넣습니다.

- Java runtime
- Pulumi CLI
- CSP credential 주입 경로

Go toolchain 과 Pulumi program 소스는 필요 없습니다. provisioning 은 Java SDK 의
backend JVM 이 YAML 프로그램을 만들고 Pulumi CLI 가 실행합니다.

이 방식은 가장 단순하고 PoC 속도가 빠릅니다.

### 대안: 별도 pulumi-runner 서비스

```text
Gateway -> anycloud-backend -> pulumi-runner
```

이 방식은 나중에 다음 상황에서 고려하면 좋습니다.

- Pulumi 실행 권한을 따로 격리하고 싶을 때
- 동시 프로비저닝 요청이 많을 때
- Spring 이미지를 가볍게 유지하고 싶을 때

## Compose 권장 형태

기본 서비스는 다음과 같습니다.

- `gateway`
- `anycloud-backend`
- `anycloud-db`

선택 서비스는 다음과 같습니다.

- `rabbitmq` (workflow messaging)
- `pulumi-runner`

현재 리포지토리에는 `anycloud-backend` + `anycloud-db` 가 이미 있으므로,
Pulumi 확장용 오버레이 compose 를 추가하는 방식이 안전합니다.

## 볼륨 및 상태 관리

Pulumi 실행 시 아래 경로는 volume 또는 외부 backend 를 고려해야 합니다.

- `/home/anycloud/.pulumi`
- `/tmp/pulumi` (호출마다 생성되는 workDir)
- kubeconfig export 디렉터리
- SSH private key export 디렉터리

권장 사항은 다음과 같습니다.

- Pulumi state 는 로컬 파일 대신 self-hosted S3 호환 backend 를 사용합니다.
- Pulumi plugin cache 는 volume 을 사용합니다.

## Self-hosted state backend (RustFS + OpenBao)

외부 SaaS 의존을 배제하고 모든 컴포넌트를 사내 Docker / K8s 위에 운영합니다.

### 컴포넌트

| 역할 | 컴포넌트 | 라이선스 |
| --- | --- | --- |
| S3 호환 object storage | RustFS | Apache 2.0 |
| Secrets transit engine | OpenBao | MPL 2.0 |

Pulumi 코드 자체는 변경하지 않고 endpoint 만 사내 컴포넌트로 가리킵니다.
RustFS 는 S3 API v4 호환이며, OpenBao 는 Vault transit engine 호환이라
Pulumi 의 `s3://` backend 와 `hashivault://` secrets provider 가 그대로 동작합니다.

### 환경변수 계약

| 변수 | 의미 | 예시 |
| --- | --- | --- |
| `PULUMI_BACKEND_URL` | state backend URL | `s3://pulumi-state?endpoint=http://rustfs:9000&s3ForcePathStyle=true&disableSSL=true` |
| `AWS_ACCESS_KEY_ID` / `AWS_SECRET_ACCESS_KEY` | RustFS root credential | `anycloud` / `anycloud-secret` (dev), 운영은 별도 서비스 계정 |
| `AWS_REGION` | RustFS 가 형식상 요구 (실제 무시) | `us-east-1` |
| `PULUMI_SECRETS_PROVIDER` | `passphrase` 또는 `hashivault://openbao:8200/<key>` | `passphrase` (dev) / `hashivault://openbao:8200/anycloud-pulumi` (staging+) |
| `PULUMI_CONFIG_PASSPHRASE` | secrets-provider=passphrase 일 때 사용 | 32+ chars 무작위 |
| `VAULT_ADDR` / `VAULT_TOKEN` | secrets-provider=hashivault 일 때 사용 | `http://openbao:8200` / dev token |
| `VAULT_NAMESPACE` | 운영 시 namespace 격리 사용 시 | `anycloud` |

본 리포지토리의 `application.yaml` 은 위 변수를 그대로 받아 `PulumiProperties` 로 바인딩합니다.
`docker-compose.dev.yml` 에 `rustfs`, `openbao`, `pulumi-backend-bootstrap` 서비스가 포함되어
`docker compose -f docker-compose.dev.yml up` 한 번으로 dev 환경 전체가 기동됩니다.

### Dev 모드 초기화 절차

```bash
# 1) 전체 기동
docker compose -f docker-compose.dev.yml up -d

# 2) RustFS 버킷 자동 생성 (pulumi-backend-bootstrap 서비스가 처리)
docker compose -f docker-compose.dev.yml logs pulumi-backend-bootstrap

# 3) OpenBao 에 transit engine 활성화 + 키 등록 (secrets-provider=hashivault 사용 시)
docker compose -f docker-compose.dev.yml exec openbao bao secrets enable transit
docker compose -f docker-compose.dev.yml exec openbao bao write -f transit/keys/anycloud-pulumi

# 4) Pulumi 가 backend 에 정상 접속하는지 확인
docker compose -f docker-compose.dev.yml exec anycloud-backend pulumi login --help
```

`docker compose` 만으로 Pulumi backend, secrets 가 같이 올라가므로
사용자는 `PULUMI_PASSPHRASE` (또는 OpenBao 모드일 때 `VAULT_TOKEN`) 만 안전하게 주입하면 됩니다.

### 운영 환경 점검 체크리스트

| 영역 | 항목 | 비고 |
| --- | --- | --- |
| RustFS | 분산 모드 (≥4 노드) | 단일 노드는 PoC 한정 |
| RustFS | S3 v4 signature 호환 검증 | `aws --endpoint=... s3 cp` 통과 확인 |
| RustFS | `s3ForcePathStyle=true` 필수 | subdomain 기반 미지원 가정 |
| RustFS | bucket replication | 별도 RustFS 클러스터 또는 외부 백업 |
| RustFS | TLS | 사내 PKI 인증서, `disableSSL=true` 제거 |
| OpenBao | Raft HA (≥3 노드) | Vault 와 동일 storage backend |
| OpenBao | transit 키 회전 정책 | `bao write transit/keys/anycloud-pulumi/rotate` |
| OpenBao | 인증 방식 | K8s 배포 시 K8s auth method 권장(token 노출 없음) |
| Pulumi | concurrent `pulumi up` lock 충돌 검증 | RustFS write 무결성 검증용 부담 테스트 |
| Pulumi | `pulumi stack change-secrets-provider` 마이그레이션 시나리오 | passphrase → hashivault 전환 사례 |

### Credential 주입 (CSP 자격증명)

위 backend 자격증명과 별개로, Pulumi 가 CSP API 를 호출하기 위한 자격증명은
환경변수 또는 secret mount 로 주입합니다. `application.yaml` 의 `pulumi.environment` 맵에
누적됩니다.

- AWS — `AWS_ACCESS_KEY_ID`, `AWS_SECRET_ACCESS_KEY`, `AWS_REGION`
- Azure 는 `ARM_CLIENT_ID`, `ARM_CLIENT_SECRET`, `ARM_TENANT_ID`, `ARM_SUBSCRIPTION_ID` 입니다.
- GCP — `GOOGLE_CREDENTIALS` 또는 `GOOGLE_APPLICATION_CREDENTIALS`
- OpenStack — `OS_AUTH_URL` 등

나머지 CSP 도 같은 패턴을 따릅니다.

> **주의**: backend 용 `AWS_*` 변수와 CSP(AWS) 용 `AWS_*` 변수가 충돌합니다.
> RustFS backend 와 AWS CSP 를 동시에 쓰려면 CSP 자격증명은 `PULUMI_CONFIG`
> stack-level 로 주입하거나, backend 만 다른 endpoint 의 별도 계정으로 분리합니다.

## Spring 내부 설계 권장안

### 컴포넌트 분리

- `PulumiProperties` — `runtimeDir`, `stackPrefix`, `backendUrl`, `secretsProvider`,
  `passphrase`, `environment`
- `PulumiCommandService` 는 CLI 실행을 담당합니다.
- `AutomationProvisioningService` — stack 이름 생성, config 주입,
  up / preview / destroy / output 오케스트레이션
- `ClusterProvisioningFacade` 는 기존 `ClusterService` 와 연결됩니다.

현재 리포지토리 기준 권장 분리는 다음과 같습니다.

- `ClusterEntity` 는 kubeconfig 기반으로 실제 Kubernetes API 에 붙는 운영 엔티티
- `ClusterProvisioningEntity` 는 Pulumi infra 생성 상태와 raw output 을 저장하는 엔티티

즉, `프로비저닝 완료` 와 `운영용 클러스터 등록 완료` 를 같은 개념으로 두지 않습니다.
이 분리는 현재 프로젝트의 kubeconfig 중심 구조와 잘 맞습니다.

### 상태 모델

권장 상태는 다음과 같습니다.

- `REQUESTED`
- `PROVISIONING`
- `READY`
- `FAILED`
- `DELETING`
- `DELETED`

Gateway/프론트 polling 기준 권장 의미는 다음과 같습니다.

- `REQUESTED` 는 요청은 저장되었고 아직 worker 가 시작 전 상태입니다.
- `PROVISIONING` 은 Pulumi 실행 중인 상태입니다.
- `READY` 는 infra 생성 성공, kubeconfig 등록 완료, cluster API 사용 가능 상태입니다.
- `FAILED` 는 프로비저닝 또는 kubeconfig 등록 실패 상태입니다.
- `DELETING` 은 `stack.destroy()` 또는 stack cleanup 진행 중인 상태입니다.
- `DELETED` 는 클러스터 연결 정보와 Pulumi stack 정리 완료 상태입니다.

## 동시 실행 한도

CSP 여러 개를 동시에 올릴 때 실질 동시성은 아래 셋 중 가장 작은 값입니다. 기본값은 CSP 8종을
한 번에 처리하도록 맞춰져 있습니다.

| 지점 | 설정 | 기본값 | 넘치면 |
|---|---|---|---|
| RabbitMQ 리스너 | `SPRING_RABBITMQ_CONCURRENCY` | 8 | 큐에서 대기 |
| Pulumi bulkhead | `PULUMI_MAX_CONCURRENT` | 8 | `PULUMI_MAX_WAIT`(30m) 동안 대기 후 실패 |
| 로컬 워크플로 풀 | `ASYNC_PROVISIONING_CORE` | 8 | 큐 30개까지 대기 |

세 가지가 함께 걸리는 함정이 있습니다.

- `SPRING_RABBITMQ_PREFETCH`가 크면 consumer 하나가 대기 메시지를 전부 선점해, 동시성을 올려도
  나머지 consumer가 놉니다. 작업이 수십 분이므로 1로 둡니다.
- `ThreadPoolTaskExecutor`는 큐가 가득 차야 core를 넘어 늘어납니다. 큐가 크면 max에 닿지 않으므로
  실질 동시성은 core 값입니다. max만 올리는 것은 효과가 없습니다.
- bulkhead 대기 시간이 작업 시간보다 짧으면 큐잉이 아니라 실패가 됩니다.

동시 실행은 메모리와 CSP API rate limit에 함께 걸립니다. 늘리기 전에
`resilience4j_bulkhead_available_concurrent_calls{name="pulumi"}`와 컨테이너 메모리를 봅니다.

## 이미지 구성

`Dockerfile.pulumi` 는 4단계입니다. 플러그인 다운로드가 앱 코드나 apk 목록 변경과 분리되어야
재빌드가 빠릅니다.

| 스테이지 | 하는 일 |
|---|---|
| `gradle-builder` | bootJar 빌드 후 `jarmode=tools extract --layers` 로 레이어 분리 |
| `cli-fetch` | pulumi, helm 바이너리. 쓰지 않는 language host 제거 |
| `plugin-fetch` | provider 플러그인 설치 |
| runtime | Alpine JRE + 위 산출물 조립 |

크기는 provider 플러그인이 지배합니다. 2,878MB 중 2,252MB(78%)가 플러그인입니다.

| 항목 | 크기 |
|---|---:|
| aws 플러그인 | 943.5 MB |
| azure-native 플러그인 | 587.8 MB |
| oci 플러그인 | 349.6 MB |
| gcp 플러그인 | 243.8 MB |
| proxmoxve 플러그인 | 82.1 MB |
| tls 플러그인 | 69.6 MB |
| openstack 플러그인 | 45.4 MB |
| app lib 의존성 | 168.7 MB |
| JRE | 156.7 MB |
| Pulumi CLI | 98.8 MB |
| helm | 57.4 MB |
| app jar | 18.6 MB |

`PULUMI_PLUGINS` 를 좁히면 해당 행이 통째로 빠집니다. OpenStack 만 쓰는 배포는 641MB 입니다.

```bash
docker build -f Dockerfile.pulumi \
  --build-arg PULUMI_PLUGINS="openstack:5.5.1 tls:5.6.0" -t anycloud:openstack .
```

### 이 구성에서 지켜야 하는 것

Pulumi 는 플러그인을 `$PULUMI_HOME/plugins` 에서만 찾습니다. `PULUMI_PLUGIN_CACHE_DIR` 은 읽지
않고 심볼릭 링크도 따라가지 않습니다. 다른 경로에 두면 캐시를 통째로 무시하고 프로비저닝마다
다시 받습니다.

`pulumi plugin install` 은 디렉터리를 `0700 root` 로, `/app` 을 `0700 root` 로 만듭니다. 컨테이너는
`USER anycloud` 로 돌기 때문에 소유권을 설치와 같은 레이어에서 잡아야 합니다. 뒤에서 `chown -R`
하면 1.8GB 를 복제한 레이어가 하나 더 생깁니다.

`COPY` 뒤의 `chown -R` 도 같은 이유로 피합니다. `COPY --chown` 을 씁니다.

서드파티 플러그인은 GitHub 릴리스에서 받습니다. 익명 요청은 IP 당 시간당 60회라 CI 에서
걸립니다. `GITHUB_TOKEN` 을 build secret 으로 넘기면 5000회가 됩니다.

```bash
docker build -f Dockerfile.pulumi --secret id=github_token,env=GITHUB_TOKEN -t anycloud .
```

### distroless, native image 를 쓰지 않는 이유

런타임이 `pulumi`, `helm`, `ssh` 바이너리를 실행하고 entrypoint 가 셸 스크립트입니다. distroless
에는 셋 다 없어 전부 되넣어야 하고, 그러면 distroless 가 아닙니다. Alpine JRE 대비 크기 차이도
24MB 입니다.

GraalVM native image 는 JRE 157MB 와 jar 일부를 줄이지만, 지배적인 플러그인 2,252MB 는 그대로
입니다. JPA, Jackson, Pulumi Automation SDK 가 리플렉션 기반이라 메타데이터 비용도 큽니다.

## 운영 시 주의점

- Pulumi 실행은 API 요청 thread 에서 직접 처리하지 않습니다.
- stdout/stderr 전체를 로깅하고 마지막 에러를 DB 에도 남깁니다.
- stack 이름 규칙을 강제합니다.
  - 예: `anycloud-aws-dev-demo`
- destroy 시 partial failure 처리 전략이 필요합니다.
- provider 별 quota 체크를 선행하면 장애가 줄어듭니다.

