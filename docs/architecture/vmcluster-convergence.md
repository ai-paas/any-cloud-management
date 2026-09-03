# VM 클러스터 컴포넌트 수렴

VM 생성 이후 계층(GPU 드라이버, GPU operator, ingress, cluster agent)을 요청한 사양에 맞을 때까지
재시도하고, 맞지 않는 상태를 숨기지 않고 드러내기 위한 설계입니다.

관련 문서는 [vmcluster-workflow.md](vmcluster-workflow.md),
[vmcluster-state-machine.md](vmcluster-state-machine.md),
[bootstrap-strategy-pattern.md](bootstrap-strategy-pattern.md) 입니다.

## 1. 무엇을 푸는 문제인가

현재 워크플로우는 `PROVISION → BOOTSTRAP → VERIFY → READY` 의 일회성 파이프라인입니다.
BOOTSTRAP 안에서 실행되는 하위 작업들의 실패 처리가 서로 다릅니다.

| 하위 작업 | 위치 | 실패 시 동작 |
|---|---|---|
| kubeadm init/join | `GenericLinuxVmClusterBootstrapStrategy` | 워크플로우 실패 |
| GPU 드라이버 설치 | 같은 클래스 `gpuPreparationCommand` | `\|\| true` 로 무시 |
| GPU operator 대기 | 같은 클래스 `kubectl wait` | `\|\| true` 로 무시 |
| cluster agent 설치 | `VmClusterAgentInstaller.installViaSsh` | 예외를 삼키고 warn 로그 |

VERIFY 단계는 `kubectl get --raw=/readyz` 만 확인합니다. 결과적으로 `READY` 는
"Kubernetes API 가 응답한다" 는 뜻이지 "요청한 사양대로 준비되었다" 는 뜻이 아닙니다.
`workerInstanceType=gpu-large` 로 요청한 클러스터가 GPU 드라이버 없이 `READY` 가 될 수 있습니다.

근본 원인은 재시도 주체가 없다는 데 있습니다. SSH 한 번 실행하고 끝나는 구조에는 다시 시도할 주체도,
수렴했는지 판단할 기준도 없습니다. `VmClusterDriftService` 가 드리프트를 감지하지만 Pulumi 가 관리하는
VM 계층만 대상입니다.

## 2. 컴포넌트 모델

VM 위에 올라가는 각 계층을 **cluster component** 로 명시합니다. 컴포넌트는 세 가지를 답합니다.

```java
public interface ClusterComponent {

    ComponentType type();

    /** 요청 사양에 비추어 이 컴포넌트가 필요한지 (desired state). */
    Requirement requirementFor(VmClusterInternalRequestSnapshot spec);

    /** 멱등 적용. 이미 적용된 상태면 아무 일도 하지 않습니다. */
    void apply(VmClusterEntity cluster, Map<String, Object> outputs);

    /** 실제로 동작 중인지 (observed state). apply 없이 단독 호출 가능해야 합니다. */
    ComponentProbe probe(VmClusterEntity cluster, Map<String, Object> outputs);
}
```

`probe` 가 `apply` 와 분리되는 것이 핵심입니다. 지금은 "설치 명령을 실행했다" 가 곧 "설치되었다" 로
취급되는데, `|| true` 가 붙은 순간 그 등식이 깨집니다.

| 컴포넌트 | 필요 조건 | apply | probe |
|---|---|---|---|
| `GPU_DRIVER` | `enableGpuOperator` | SSH `ubuntu-drivers install --gpgpu` | GPU 노드에서 `nvidia-smi -L` 이 장치를 나열 |
| `GPU_OPERATOR` | `enableGpuOperator` | helm `nvidia/gpu-operator` | 노드 allocatable `nvidia.com/gpu` 합계 > 0 |
| `INGRESS` | `enableIngress` | 기존 `ingressInstallCommand` | ingress controller deployment 가 available |
| `AGENT` | agent 기능 활성 | manifest `kubectl apply` | agent gRPC 연결이 ACTIVE |

`ComponentProbe` 는 `ComponentHealth` 와 사유 문자열을 담습니다. 사유는 `last_error` 로 저장되고
API 로 그대로 노출되므로 자격증명을 담지 않습니다.

`ComponentHealth` 는 세 값입니다.

| 값 | 의미 |
|---|---|
| `READY` | probe 가 충족을 확인했습니다. |
| `NOT_READY` | probe 가 미충족을 확인했습니다. |
| `UNKNOWN` | probe 자체가 실패했습니다 (SSH 불통, 타임아웃). |

`UNKNOWN` 을 `NOT_READY` 와 구분하는 이유는 두 사건의 성격이 다르기 때문입니다. SSH 가 잠깐 끊긴 것을
미충족으로 판정하면 네트워크가 흔들릴 때마다 클러스터 상태가 오갑니다. `UNKNOWN` 은 상태 전이를
일으키지 않고 다음 주기를 기다립니다.

### 컴포넌트로 만들지 않는 것

**kubeadm** 은 제외합니다. 실패하면 클러스터가 존재하지 않는 것이므로 지금처럼 `FAILED` 로 가는 것이
맞습니다. 수렴 대상은 "클러스터는 살아있는데 요청한 사양에 못 미치는" 경우입니다.

**addon** (`MONITORING`, `VELERO`, `CERT_MANAGER` 등)도 제외합니다. `AddonInstaller` 와
`AddonState` 로 이미 별도 추적되고 있어 모델을 이중으로 만들 필요가 없습니다. 다만 조정 루프가
`FAILED` 상태의 addon 을 재큐잉하는 것까지는 이 설계에 포함합니다.

## 3. 등급과 자동 복구 정책

컴포넌트마다 두 가지 속성을 둡니다.

| 속성 | 값 | 결정하는 것 |
|---|---|---|
| `requirement` | `REQUIRED` / `BEST_EFFORT` / `NOT_APPLICABLE` | `READY` 판정에 반영할지 |
| `autoRepair` | `true` / `false` | 조정 루프가 스스로 `apply` 를 다시 호출할지 |

### requirement

전부 `READY` 의 전제조건으로 만들면 개발 환경이 깨집니다. `VmClusterAgentInstaller` javadoc 이
지적하듯 agent 가 ACTIVE 로 전환되려면 백엔드 gRPC 엔드포인트가 CSP VM 에서 도달 가능해야 하는데,
개발 기본값(`host.docker.internal`)은 그렇지 않습니다.

| 컴포넌트 | 기본 requirement | 근거 |
|---|---|---|
| `GPU_DRIVER` | GPU 요청 시 `REQUIRED` | 운영자가 명시적으로 요청한 사양입니다. |
| `GPU_OPERATOR` | GPU 요청 시 `REQUIRED` | 같습니다. |
| `INGRESS` | `enableIngress` 시 `REQUIRED` | 같습니다. |
| `AGENT` | `REQUIRED` (개발 프로파일에서 `BEST_EFFORT`) | 도달성이 환경에 의존합니다. |

agent 등급은 `anycloud.vm-cluster.component.agent.requirement` 로 정합니다. 지금처럼 코드에
하드코딩된 best-effort 는 운영에서 되돌릴 방법이 없습니다.

### autoRepair

| 컴포넌트 | autoRepair | 근거 |
|---|---|---|
| `GPU_OPERATOR` | `true` | helm upgrade --install 은 멱등이고 클러스터 내부 리소스만 건드립니다. |
| `INGRESS` | `true` | 같습니다. |
| `AGENT` | `true` | manifest 재적용은 멱등입니다. |
| `GPU_DRIVER` | **`false`** | 아래 참조 |

`GPU_DRIVER` 만 자동 복구에서 제외합니다. `ubuntu-drivers install --gpgpu` 는 커널 모듈을 올리고
경우에 따라 노드 재부팅을 요구합니다. 워크로드가 이미 돌고 있는 노드에서 이를 자동으로 재실행하는 것이
안전하다고 확신할 수 없습니다.

따라서 `GPU_DRIVER` 는 이렇게 동작합니다.

- **수렴 루프**(클러스터 생성 직후, 워크로드 없음)에서는 apply 합니다.
- **조정 루프**에서는 probe 만 하고, 미충족이면 `DEGRADED` 를 유지한 채 드러냅니다.
- 재적용은 운영자가 `POST /v1/vm-clusters/{id}/components/{type}/repair` 로 명시 요청합니다.

`autoRepair` 를 컴포넌트별 속성으로 둔 것은 이 판단이 바뀔 수 있기 때문입니다. CSP 별 드라이버 설치
거동이 확인되면 설정만 바꾸면 됩니다.

## 4. 상태 모델

`VmClusterStatus` 에 `DEGRADED` 를 추가합니다.

| 상태 | 의미 |
|---|---|
| `READY` | Kubernetes API 정상이며 `REQUIRED` 컴포넌트가 전부 충족되었습니다. |
| `DEGRADED` | Kubernetes API 는 정상이나 `REQUIRED` 컴포넌트 일부가 미충족입니다. 조정 루프가 계속 시도합니다. |
| `FAILED` | 클러스터 자체가 성립하지 않습니다. |

`FAILED` 로 보내지 않는 이유는, VM 이 이미 떠 있고 과금되는데 워크플로우만 죽은 상태를 만들지 않기
위해서입니다. `DEGRADED` 는 사용 가능한 클러스터이면서 동시에 "아직 요청대로가 아니다" 를 정확히
표현합니다.

### 전이 그래프 변경

`VmClusterStatus.ALLOWED_TRANSITIONS` 한 곳만 바꿉니다.

| From | To | 계기 |
|---|---|---|
| `VERIFYING` | `DEGRADED` | 수렴 루프가 시간 안에 수렴시키지 못했습니다. |
| `DEGRADED` | `READY` | 조정 루프가 수렴시켰습니다. |
| `DEGRADED` | `FAILED` | 클러스터 자체가 깨졌습니다. |
| `DEGRADED` | `DELETING` | 삭제 요청입니다. |
| `READY` | `DEGRADED` | 조정 루프가 드리프트를 감지했습니다. |

```mermaid
stateDiagram-v2
    VERIFYING --> READY: 전부 충족
    VERIFYING --> DEGRADED: REQUIRED 미충족
    DEGRADED --> READY: 조정 루프 수렴
    READY --> DEGRADED: 드리프트 감지
    DEGRADED --> FAILED: 클러스터 손상
    DEGRADED --> DELETING: 삭제
```

`VmClusterWorkflowStep.isStaleForStatus` 에서 `DEGRADED` 는 VERIFY 재진입을 막지 않아야 합니다.
재시도가 목적인 상태이기 때문입니다. `VmClusterStatus.isInProgress()` 에는 포함하지 않습니다.
자동 진행 중이 아니라 수렴 대기 상태입니다.

## 5. 두 개의 루프

### 수렴 루프 (VERIFY 단계 내부, 시간 제한 있음)

`REQUIRED` 컴포넌트를 apply 한 뒤 probe 하고, 미충족이면 짧은 백오프로 재시도합니다.
**3회, 총 3분을 넘기지 않습니다.**

VERIFY 에 두는 이유는 READY 를 결정하는 곳이 VERIFY 이고, agent 설치가 BOOTSTRAP 끝에서
일어나 같은 단계에서 probe 하면 항상 이르기 때문입니다.

여기서 오래 붙잡지 않는 것이 중요합니다. 이 코드는 RabbitMQ consumer 스레드 위에서 실행되므로
GPU operator 가 뜨기를 15분 기다리면 그동안 consumer 하나가 통째로 묶입니다. 현재 SSH 스크립트의
`kubectl wait --timeout=15m` 이 정확히 그 문제를 갖고 있습니다.

시간 안에 수렴하지 않으면 `DEGRADED` 로 전이하고 조정 루프에 넘깁니다.

### 조정 루프 (`@Scheduled` + ShedLock)

`READY` 와 `DEGRADED` 클러스터를 대상으로 `REQUIRED` 컴포넌트를 probe 합니다. 기본 주기는 5분이며
`anycloud.vm-cluster.convergence.interval-ms` 로 조정합니다.

| probe 결과 | 현재 상태 | 동작 |
|---|---|---|
| 전부 `READY` | `DEGRADED` | `READY` 로 전이 |
| 일부 `NOT_READY` | `READY` | `DEGRADED` 로 전이 후 `autoRepair` 대상만 재적용 |
| 일부 `NOT_READY` | `DEGRADED` | 백오프가 지난 `autoRepair` 대상만 재적용 |
| `UNKNOWN` 포함 | 무관 | 기록만 하고 상태 전이 없음 |

`FleetUpgradeOrchestratorImpl.drive()` 와 동일한 형태를 사용합니다. 다중 replica 에서 리더 하나만
도는 것은 기존 `ShedLockConfig` 가 보장합니다.

## 6. 백오프와 실패 분류

컴포넌트별로 시도 횟수와 다음 시도 시각을 기록합니다. 지수 백오프에 상한을 둡니다.

```
1분 → 2분 → 4분 → 8분 → 16분 → 32분 → 이후 1시간 고정
```

워크플로우의 `BLOCKED` 처럼 완전히 정지시키지는 않습니다. GPU 드라이버 설치는 CSP 쿼터나 이미지 문제로
몇 시간 뒤에 성공할 수 있고, 사람이 개입해야만 재시도되는 구조는 현재와 크게 다르지 않습니다. 대신 시도
횟수와 마지막 오류를 노출해 운영자가 판단할 수 있게 합니다.

영구 실패는 재시도해도 소용없으므로 즉시 상한 백오프로 보냅니다. 판별은 `CspStderrClassifier` 를
확장해 처리합니다.

| 분류 | 예시 | 동작 |
|---|---|---|
| 일시 실패 | SSH 타임아웃, helm repo 일시 오류 | 지수 백오프로 재시도 |
| 영구 실패 | Ubuntu 계열이 아닌 이미지, GPU 없는 인스턴스 타입 | 상한 백오프 + 오류 노출 |

## 7. 데이터 모델

```sql
CREATE TABLE vm_cluster_component (
    id              VARCHAR(64)  NOT NULL PRIMARY KEY,
    vm_cluster_id   VARCHAR(64)  NOT NULL,
    component_type  VARCHAR(32)  NOT NULL,
    requirement     VARCHAR(16)  NOT NULL,
    auto_repair     BOOLEAN      NOT NULL DEFAULT TRUE,
    health          VARCHAR(16)  NOT NULL,
    attempts        INT          NOT NULL DEFAULT 0,
    next_attempt_at DATETIME(6)  NULL,
    last_probed_at  DATETIME(6)  NULL,
    last_applied_at DATETIME(6)  NULL,
    last_error      TEXT         NULL,
    created_at      DATETIME(6)  NOT NULL,
    updated_at      DATETIME(6)  NOT NULL,
    CONSTRAINT uk_vm_cluster_component UNIQUE (vm_cluster_id, component_type),
    CONSTRAINT fk_vm_cluster_component_cluster
        FOREIGN KEY (vm_cluster_id) REFERENCES vm_cluster (id) ON DELETE CASCADE
);
```

`id` 는 `varchar(64)` 입니다. `cluster_addon.id` 에서 42자 값에 `varchar(36)` 컬럼이 생성되어
insert 가 항상 깨졌던 것과 같은 실수를 반복하지 않기 위해 Flyway 정의와 엔티티 `@Column(length)` 를
같은 값으로 맞춥니다.

desired state 는 이미 `vm_cluster.request_config` 에 JSON 으로 영속화되어 있어
(`VmClusterInternalRequestSnapshot`) 별도 저장이 필요 없습니다. 조정 루프는 매 주기 이 스냅샷을 읽어
`requirementFor` 를 다시 계산합니다.

## 8. API

`GET /v1/vm-clusters/{id}` 응답에 컴포넌트 배열을 추가합니다. `DEGRADED` 인데 이유를 알 수 없으면
상태를 추가한 의미가 없습니다.

```json
{
  "provisioningStatus": "DEGRADED",
  "components": [
    {
      "type": "GPU_DRIVER",
      "requirement": "REQUIRED",
      "health": "READY",
      "lastProbedAt": "2026-09-03T10:15:00Z"
    },
    {
      "type": "GPU_OPERATOR",
      "requirement": "REQUIRED",
      "health": "NOT_READY",
      "attempts": 4,
      "nextAttemptAt": "2026-09-03T10:22:00Z",
      "lastError": "no node reports allocatable nvidia.com/gpu"
    }
  ]
}
```

수동 복구용으로 엔드포인트를 하나 추가합니다.

| 메서드 | 경로 | 용도 |
|---|---|---|
| `POST` | `/v1/vm-clusters/{id}/components/{type}/repair` | `autoRepair=false` 컴포넌트의 재적용을 명시 요청합니다. 백오프를 초기화합니다. |

## 9. 함께 정리하는 결함

| 항목 | 현재 | 조치 |
|---|---|---|
| `ClusterSpec.enableGpuOperator` | Pulumi `ClusterSpec` 에 파싱되지만 provisioner 7종 중 읽는 곳이 없습니다. | Pulumi 쪽에서 제거합니다. GPU operator 는 Kubernetes 계층 관심사이므로 컴포넌트 모델이 소유합니다. |
| `docs/architecture/pulumi/pulumi-gpu-support.md` | "`ClusterSpec.enableGpuOperator` 가 true 이면 Pulumi 가 다음을 처리합니다" 라고 서술합니다. | 사실과 다릅니다. 실제 설치 주체는 SSH bootstrap 전략입니다. 재작성합니다. |
| `GpuFlavorMapper` javadoc | "본 flag 가 true 면 Pulumi 가 nvidia/gpu-operator helm 을 설치" 라고 서술합니다. | 같은 이유로 수정합니다. |
| `kubectl wait --timeout=15m` | consumer 스레드를 최대 15분 점유합니다. | 짧은 probe 로 대체하고 대기는 조정 루프가 담당합니다. |
| `\|\| true`, 예외 삼킴 | 실패가 기록되지 않습니다. | 제거합니다. 실패는 컴포넌트 health 로 기록됩니다. |

## 10. 적용 단계

| 단계 | 내용 | 이 단계만으로 얻는 것 |
|---|---|---|
| 1 | 컴포넌트 인터페이스, 테이블, probe 구현. **apply 는 기존 SSH 경로를 유지하고 probe 로 관측만 합니다.** | 현재 실제 실패율 실측 |
| 2 | `DEGRADED` 상태와 조정 루프 추가 | 재시도와 수렴 동작 |
| 3 | apply 를 SSH 스크립트에서 컴포넌트로 이관하고 `\|\| true` 제거 | 단계 소유권 정리 |
| 4 | agent 등급 설정화, API 노출, 수동 복구 엔드포인트 | 운영 가시성 |

1단계를 관측 전용으로 두는 이유는, 현재 실패율을 모르는 상태에서 `DEGRADED` 를 켜면 그동안 정상으로
보이던 클러스터가 대량으로 `DEGRADED` 로 바뀔 수 있기 때문입니다. 먼저 측정합니다.

각 단계는 독립적으로 배포 가능합니다.

## 11. 테스트

| 대상 | 방법 |
|---|---|
| 컴포넌트 probe | SSH 실행기를 stub 으로 두고 `nvidia-smi` 출력별 판정을 검증합니다. |
| 상태 전이 | `canTransitionTo` 표 기반 테스트에 `DEGRADED` 행을 추가합니다. |
| 백오프 | 고정 `Clock` 을 주입해 시도 횟수별 `next_attempt_at` 을 검증합니다. |
| 조정 루프 | 컴포넌트 health 를 조작하고 `drive()` 1회 실행 후 상태를 확인합니다. |
| 수렴 시간 제한 | 항상 `NOT_READY` 인 컴포넌트로 BOOTSTRAP 이 3분 안에 `DEGRADED` 를 반환하는지 확인합니다. |
| `UNKNOWN` 처리 | probe 가 예외를 던질 때 상태 전이가 일어나지 않는지 확인합니다. |
| autoRepair=false | 조정 루프가 `GPU_DRIVER` 에 apply 를 호출하지 않는지 확인합니다. |

## 12. 이 설계에서 다루지 않는 것

| 항목 | 이유 |
|---|---|
| Pulumi Java SDK 를 생성 YAML 로 교체 (빌드 산출물 732MB 제거) | `stackOutputs()` 의 `Map<String, Object>` 계약만 공유하고 서로를 바꾸지 않습니다. 별도 설계로 분리합니다. |
| Cluster API 이전 | 조정 루프 모델의 상위 호환이지만 프로비저닝 도메인 전체를 재작성하는 규모이며, Alibaba provider 가 CAPI 공식 목록에서 unofficial 입니다. 이 설계로 단계 경계가 정리되면 개별 단계를 이관하는 선택지가 열립니다. |
| 메시지 브로커 교체 | [13절](#13-메시지-브로커에-관한-검토) 참조 |
| kubeadm 단계의 수렴 | 실패 시 클러스터가 성립하지 않으므로 `FAILED` 처리가 맞습니다. |

## 13. 메시지 브로커에 관한 검토

Redis 를 메시지 큐로 쓰는 방안을 검토했고, **현행 RabbitMQ 를 유지합니다.**

현재 `RabbitMqVmClusterWorkflowConfiguration` 이 사용하는 기능은 다음과 같습니다.

| 기능 | 구현 |
|---|---|
| dead letter 라우팅 | 큐 인자 `x-dead-letter-exchange`, `x-dead-letter-routing-key` |
| 지수 백오프 재시도 | `RetryInterceptorBuilder.stateless()` + `ExponentialBackOff` |
| 영구 실패 즉시 DLQ | `ExceptionClassifierRetryPolicy` 로 예외 클래스별 `NeverRetryPolicy` 적용 |
| 내구성 | `QueueBuilder.durable()` |

Redis 에서 이에 대응할 수 있는 것은 Streams 의 consumer group 뿐입니다(Pub/Sub 은 영속성이 없고
List 는 ack 가 없습니다). Streams 는 `XPENDING` 과 `XAUTOCLAIM` 으로 재전달을 제공하지만 위 표의
나머지는 직접 구현해야 합니다.

| 항목 | RabbitMQ | Redis Streams |
|---|---|---|
| ack, 재전달 | 기본 제공 | `XACK`, `XAUTOCLAIM` 으로 가능 |
| DLQ | 큐 인자 선언만으로 동작 | 별도 stream 과 이관 로직을 직접 구현 |
| 지수 백오프 | Spring AMQP advice chain | 직접 구현 |
| 예외별 재시도 정책 | `ExceptionClassifierRetryPolicy` | 직접 구현 |
| 신규 인프라 | 이미 운영 중 | compose 에 서비스 추가 필요 |

바꿔서 얻는 것은 처리량인데, VM 프로비저닝은 건당 수 분이 걸리는 저빈도 작업이라 처리량이 병목이 아닙니다.
반대로 잃는 것은 전달 보증과 이미 검증된 재시도 구성입니다. 이 설계는 오히려 큐의 역할을 줄이는
방향이라(긴 대기를 조정 루프로 옮김) 브로커에 요구하는 바가 더 단순해집니다.

다만 Redis 자체가 이 저장소에서 쓸모없다는 뜻은 아닙니다. 현재 `VmOptionsServiceImpl` 이 CSP region,
flavor 조회 결과를 Caffeine 으로 30분 캐시하는데, Caffeine 은 프로세스 로컬입니다. 백엔드를 여러
replica 로 운영하면 replica 수만큼 CSP API 쿼터를 소비합니다. 공유 캐시가 필요해지는 시점이 오면
그때 Redis 가 후보입니다. 이는 이 설계와 무관한 별개 논의입니다.
