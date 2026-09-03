# Provider Credential Matrix

이 문서는 `VM Cluster` 생성 시 각 Provider에서 필요한 자격증명과 필수 config를 빠르게 확인하기 위한 표입니다.

## Public Providers

| Provider | Credential ENV / MANUAL key | 필수 config |
| --- | --- | --- |
| AWS | `AWS_ACCESS_KEY_ID`, `AWS_SECRET_ACCESS_KEY`, `AWS_REGION` | 없음 |
| GCP | `GOOGLE_APPLICATION_CREDENTIALS` 또는 `GOOGLE_CREDENTIALS` | `anycloud-k8s:gcpProject` |
| Azure | `ARM_CLIENT_ID`, `ARM_CLIENT_SECRET`, `ARM_TENANT_ID`, `ARM_SUBSCRIPTION_ID` | `anycloud-k8s:azureResourceGroup` |
| Alibaba | `ALICLOUD_ACCESS_KEY`, `ALICLOUD_SECRET_KEY`, `ALICLOUD_REGION` | 없음 |
| OCI | `TF_VAR_tenancy_ocid`, `TF_VAR_user_ocid`, `TF_VAR_fingerprint`, `TF_VAR_region`, `TF_VAR_private_key` 또는 `TF_VAR_private_key_path` | `anycloud-k8s:ociCompartmentId` |
| DigitalOcean | `DIGITALOCEAN_TOKEN` 또는 `DIGITALOCEAN_ACCESS_TOKEN` | 없음 |

## Private Providers

| Provider | Credential ENV / MANUAL key | 필수 config |
| --- | --- | --- |
| OpenStack | `OS_AUTH_URL`, `OS_USERNAME`, `OS_PASSWORD`, `OS_PROJECT_NAME`, `OS_USER_DOMAIN_NAME`, `OS_PROJECT_DOMAIN_NAME`, `OS_REGION_NAME` | `anycloud-k8s:openstackImageName`, `anycloud-k8s:openstackFlavorName`, 그리고 `anycloud-k8s:openstackExternalNetworkId` 또는 `anycloud-k8s:openstackFloatingIpPool` |
| Proxmox | `PROXMOX_VE_ENDPOINT`, `PROXMOX_VE_USERNAME`, `PROXMOX_VE_PASSWORD` | `anycloud-k8s:proxmoxNodeName`, `anycloud-k8s:proxmoxTemplateVmId`, `anycloud-k8s:proxmoxDatastoreId`, `anycloud-k8s:proxmoxNetworkBridge` |

## 운영 메모

- `MANUAL` credential은 `CSP_CREDENTIAL_ENCRYPTION_KEY`가 설정된 상태에서 DB에 암호화 저장합니다.
- `ENV` credential은 백엔드 컨테이너 환경변수를 그대로 참조합니다.
- Bruno CLI 테스트 시에는 `VM_CREDENTIALS_JSON` 같은 `process.env` 패턴으로 민감값을 파일 밖에서 주입하는 방식을 권장합니다.
- 실제 `Pulumi` 실행과 `delete/retry`는 같은 `credentialId`를 다시 사용합니다.
- `Proxmox`는 cloud-init snippet 업로드가 가능한 datastore 설정을 권장합니다.
- `OCI`는 tenancy/region에 따라 기본 Ubuntu 이미지 필터가 맞지 않을 수 있으므로, 실환경에서는 image 선택 정책을 별도로 점검하는 편이 안전합니다.
