# VM Cluster RabbitMQ Workflow

`VM Cluster` 생성은 이제 다음 단계형 워크플로우로 확장할 수 있습니다.

1. `Provision`
   - Pulumi가 VM, 네트워크, 보안 리소스를 생성합니다.
2. `Bootstrap`
   - bootstrap worker가 kubeconfig를 수집하고 클러스터를 등록합니다.
3. `Verify`
   - Kubernetes API 연결과 상태를 검증합니다.
4. `Ready`
   - 등록 완료 후 운영 대상 클러스터로 전환됩니다.

기본 큐 구성입니다.

| 종류 | 이름 |
|---|---|
| exchange | `vm-cluster.workflow` |
| dead-letter exchange | `vm-cluster.workflow.dlx` |
| queue | `vm-cluster.provision` |
| queue | `vm-cluster.bootstrap` |
| queue | `vm-cluster.verify` |
| queue | `vm-cluster.destroy` |
| queue (DLQ) | `vm-cluster.workflow.dlq` |

관리 API 는 `GET /v1/workflow/queues` 하나입니다.

- provision, bootstrap, verify, destroy, DLQ 큐의 적재 메시지 수와 consumer 수를 조회합니다.
  - 응답에는 `queueType`, `routingKey`, `deadLetterEnabled` 도 포함됩니다.

기본 상태 전이입니다.

`REQUESTED` → `PROVISIONING` → `BOOTSTRAPPING` → `VERIFYING` → `READY`

어느 단계에서든 실패하면 `FAILED` 로 갑니다.

필요한 환경변수입니다.

- `VM_CLUSTER_WORKFLOW_ENABLED=true`
- `SPRING_RABBITMQ_HOST`, `SPRING_RABBITMQ_PORT`
- `SPRING_RABBITMQ_USERNAME`, `SPRING_RABBITMQ_PASSWORD`

현재 구현은 다음 두 모드를 함께 지원합니다.

- `VM_CLUSTER_WORKFLOW_ENABLED=false`
  - 로컬 비동기 fallback
- `VM_CLUSTER_WORKFLOW_ENABLED=true`
  - RabbitMQ queue 기반 워크플로우

compose 기준 역할 분담입니다.

| 서비스 | 담당 단계 | 설정 |
|---|---|---|
| `anycloud-backend` | provision, destroy | `APP_ROLE=backend` |
| `anycloud-bootstrap-worker` | bootstrap, verify | `APP_ROLE=worker`, `vm-cluster-worker` profile |

bootstrap 단계는 두 서비스가 나눠 맡습니다.

- `VmClusterKubeconfigService` — SSH/SCP 로 kubeconfig 수집
- `VmClusterRegistrationService` — kubeconfig 를 `ClusterEntity` 로 등록

bootstrap 또는 verify 가 실패하면 이렇게 처리합니다.
- `vm_cluster.bootstrap_log` 에 `cloud-init-output.log`, `cloud-init status`, `kubelet journal`, `systemctl status kubelet`, `kubectl get nodes/pods` 일부를 저장합니다.
- API 응답에서는 `VM Cluster Status.bootstrapLog` 로 확인할 수 있습니다.
- bootstrap 은 node preparation, master initialization, worker join,
  cluster readiness wait, addon installation 순으로 진행하며 각 단계는 내부 재시도 후
  최종 실패로 처리합니다.

실행 참고 사항은 다음과 같습니다.

- 현재 환경에서 RabbitMQ 컨테이너 기동은 확인했습니다.
- AWS end-to-end 는 `AWS_ACCESS_KEY_ID`, `AWS_SECRET_ACCESS_KEY` 가 비어 있어 `pulumi up` 실제 실행 검증은 아직 진행하지 못했습니다.

이 구조라서 나중에 bootstrap worker 를 별도 애플리케이션으로 분리해도 메시지 계약은 그대로 유지할 수 있습니다.
