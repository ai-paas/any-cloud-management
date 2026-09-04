# VM Cluster E2E Checklist

이 문서는 `Pulumi + RabbitMQ + Bootstrap Worker` 기반 VM 클러스터 흐름을 실제로 검증할 때 확인할 항목을 정리합니다.

## 사전 조건

- `aipaas.vm_cluster` 테이블 적용
- `rabbitmq` 기동
- `anycloud-backend` 기동
- `anycloud-bootstrap-worker` 기동
- `PULUMI_ENABLED=true`
- `VM_CLUSTER_WORKFLOW_ENABLED=true`

## CSP 자격증명

### AWS
- `AWS_ACCESS_KEY_ID`
- `AWS_SECRET_ACCESS_KEY`
- `AWS_REGION`

### GCP
- `GOOGLE_APPLICATION_CREDENTIALS` 또는 `GOOGLE_CREDENTIALS`
- provisioning config에 `anycloud-k8s:gcpProject`

### Azure
- `ARM_CLIENT_ID`
- `ARM_CLIENT_SECRET`
- `ARM_TENANT_ID`
- `ARM_SUBSCRIPTION_ID`
- provisioning config에 `anycloud-k8s:azureResourceGroup`

### Alibaba
- `ALICLOUD_ACCESS_KEY`
- `ALICLOUD_SECRET_KEY`
- `ALICLOUD_REGION`
- ECS instance family / Ubuntu image availability 확인

### OpenStack
- `OS_AUTH_URL`
- `OS_USERNAME`
- `OS_PASSWORD`
- `OS_PROJECT_NAME`
- `OS_USER_DOMAIN_NAME`
- `OS_PROJECT_DOMAIN_NAME`
- `OS_REGION_NAME`
- provisioning config에 `anycloud-k8s:openstackImageName`, `anycloud-k8s:openstackFlavorName`
- floating IP pool / external network capacity 확인

### Proxmox
- `PROXMOX_VE_ENDPOINT`
- `PROXMOX_VE_USERNAME`
- `PROXMOX_VE_PASSWORD`
- provisioning config에 `anycloud-k8s:proxmoxNodeName`, `anycloud-k8s:proxmoxTemplateVmId`, `anycloud-k8s:proxmoxDatastoreId`, `anycloud-k8s:proxmoxNetworkBridge`
- snippet 저장이 가능한 datastore와 cloud-init template VM 확인

### OCI
- `TF_VAR_tenancy_ocid`
- `TF_VAR_user_ocid`
- `TF_VAR_fingerprint`
- `TF_VAR_region`
- `TF_VAR_private_key` 또는 `TF_VAR_private_key_path`
- provisioning config에 `anycloud-k8s:ociCompartmentId`
- compartment 권한 / shape / Ubuntu image availability 확인

### DigitalOcean
- `DIGITALOCEAN_TOKEN` 또는 `DIGITALOCEAN_ACCESS_TOKEN`

자세한 매트릭스는 [provider-credential-matrix.md](../api/provider-credential-matrix.md)를 참고합니다.

## 검증 순서

1. `POST /v1/cluster-validations` 로 config/credential/name 충돌을 사전 검토합니다.
   - `providerReadinessMessages`, `e2eChecklistItems` 까지 같이 확인합니다.
2. `POST /v1/clusters` body `{source:"vm", clusterName, spec:{...}}` 로 생성 요청 → 202 + Operation
   - `Location` 헤더의 `/v1/operations/{id}` 또는 `GET /v1/operations/{id}/events` (SSE) 로 진행을 추적합니다.
3. `GET /v1/clusters/{clusterName}` 로 상태 전이를 확인합니다.
4. `GET /v1/workflow/queues` 로 workflow queue 상태를 확인합니다.
5. `currentWorkflowStep`, `workflowRetryCount`, 단계별 시각을 확인합니다.
6. `bootstrapLog` 를 확인합니다.
7. `POST /v1/clusters/{clusterName}/connectivity-checks` 로 K8s API 연결을 검증합니다.
8. `DELETE /v1/clusters/{clusterName}` 로 삭제를 확인합니다 → 202 + Operation (DELETE_CLUSTER).

## 기대 상태 전이

- `REQUESTED`
- `PROVISIONING`
- `BOOTSTRAPPING`
- `VERIFYING`
- `READY`

GPU 나 ingress 를 요청했다면 `VERIFYING` 다음이 `DEGRADED` 일 수 있습니다. 실패가 아니라 구성 요소
수렴을 기다리는 상태이며, 조정 루프가 5분마다 재시도해 갖춰지면 `READY` 로 올라갑니다. 사유는
`GET /v1/vms/{name}` 의 `components` 와 `requestedAddons` 에서 확인합니다.

addon 은 agent 가 연결된 뒤에 설치되므로, agent dial-in 전까지는 `UNKNOWN`(등록 대기)이 정상입니다.

삭제 시:

- `DELETING`
- `DELETED`

## 실패 시 점검

- `bootstrapLog`
- `currentWorkflowStep`
- `lastSuccessfulStep`
- `lastFailedStep`
- `workflowRetryCount`
- `requestedAt`
- `provisioningStartedAt`
- `bootstrappingStartedAt`
- `verifyingStartedAt`
- `readyAt`
- `/var/log/cloud-init-output.log`
- `cloud-init status`
- `journalctl -u kubelet`
- RabbitMQ DLQ 적재 여부

## 현재 구현 메모

- `Pulumi`는 VM과 최소 노드 준비까지만 담당합니다.
- `Bootstrap Worker`는 `kubeadm init/join`, `CNI`, `Ingress`, `GPU Operator` 설치를 담당합니다.
- `Ingress`는 Public Cloud 계열은 `provider/cloud`, OpenStack/Proxmox는 `provider/baremetal` manifest를 사용합니다.
- `GPU driver` 자동 설치는 현재 Ubuntu 계열 이미지 기준으로만 수행합니다.
- 실제 AWS E2E는 현재 로컬 환경에 credential이 없어 아직 실행하지 못했습니다.
