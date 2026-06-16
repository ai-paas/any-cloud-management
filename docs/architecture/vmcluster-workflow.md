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

기본 큐 구성은 다음과 같습니다.

- exchange 는 `vm-cluster.workflow` 입니다.
- dead-letter exchange 는 `vm-cluster.workflow.dlx` 입니다.
- queue 는 `vm-cluster.provision` 입니다.
- queue 는 `vm-cluster.bootstrap` 입니다.
- queue 는 `vm-cluster.verify` 입니다.
- queue 는 `vm-cluster.destroy` 입니다.
- queue 는 `vm-cluster.workflow.dlq` 입니다.

관리 API 는 다음과 같습니다.

- `GET /v1/workflow/queues` 입니다.
  - provision/bootstrap/verify/destroy/DLQ 큐의 적재 메시지 수와 consumer 수를 조회합니다.
  - 응답에는 `queueType`, `routingKey`, `deadLetterEnabled` 도 포함됩니다.

기본 상태 전이는 다음과 같습니다.

- `REQUESTED` 입니다.
- `PROVISIONING` 입니다.
- `BOOTSTRAPPING` 입니다.
- `VERIFYING` 입니다.
- `READY` 입니다.
- `FAILED` 입니다.

설정은 다음과 같습니다.

- `VM_CLUSTER_WORKFLOW_ENABLED=true` 입니다.
- `SPRING_RABBITMQ_HOST` 입니다.
- `SPRING_RABBITMQ_PORT` 입니다.
- `SPRING_RABBITMQ_USERNAME` 입니다.
- `SPRING_RABBITMQ_PASSWORD` 입니다.

현재 구현은 다음 두 모드를 함께 지원합니다.

- `VM_CLUSTER_WORKFLOW_ENABLED=false`
  - 로컬 비동기 fallback
- `VM_CLUSTER_WORKFLOW_ENABLED=true`
  - RabbitMQ queue 기반 워크플로우

현재 compose 기준 역할은 다음과 같습니다.

- `anycloud-backend` 입니다.
  - provision 을 담당합니다.
  - destroy 를 담당합니다.
  - `APP_ROLE=backend` 입니다.
- `anycloud-bootstrap-worker` 입니다.
  - bootstrap 을 담당합니다.
  - verify 를 담당합니다.
  - `vm-cluster-worker` profile 입니다.
  - `APP_ROLE=worker` 입니다.

bootstrap 단계 책임 분리는 다음과 같습니다.

- `VmClusterKubeconfigService` 입니다.
  - SSH/SCP 로 kubeconfig 를 수집합니다.
- `VmClusterRegistrationService` 입니다.
  - kubeconfig 를 `ClusterEntity` 로 등록합니다.

실패 진단은 다음과 같습니다.

- bootstrap 또는 verify 실패 시 다음과 같이 처리합니다.
- `vm_cluster.bootstrap_log` 에 `cloud-init-output.log`, `cloud-init status`, `kubelet journal`, `systemctl status kubelet`, `kubectl get nodes/pods` 일부를 저장합니다.
- API 응답에서는 `VM Cluster Status.bootstrapLog` 로 확인할 수 있습니다.
- bootstrap 주요 단계는 내부 재시도 후 최종 실패 처리됩니다.
  - node preparation 입니다.
  - master initialization 입니다.
  - worker join 입니다.
  - cluster readiness wait 입니다.
  - addon installation 입니다.

실행 참고 사항은 다음과 같습니다.

- 현재 환경에서 RabbitMQ 컨테이너 기동은 확인했습니다.
- AWS end-to-end 는 `AWS_ACCESS_KEY_ID`, `AWS_SECRET_ACCESS_KEY` 가 비어 있어 `pulumi up` 실제 실행 검증은 아직 진행하지 못했습니다.

이 구조라서 나중에 bootstrap worker 를 별도 애플리케이션으로 분리해도 메시지 계약은 그대로 유지할 수 있습니다.
