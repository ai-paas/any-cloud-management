# Provider Credential Matrix

이 문서는 `VM Cluster` 생성 시 각 Provider에서 필요한 자격증명과 필수 config를 빠르게 확인하기 위한 표입니다.

## Public Providers

| Provider | Credential ENV / MANUAL key | 필수 `providerSpec` |
| --- | --- | --- |
| AWS | `AWS_ACCESS_KEY_ID`, `AWS_SECRET_ACCESS_KEY`, `AWS_REGION` | 없음 |
| GCP | `GOOGLE_APPLICATION_CREDENTIALS` 또는 `GOOGLE_CREDENTIALS` | `providerSpec.project` |
| Azure | `ARM_CLIENT_ID`, `ARM_CLIENT_SECRET`, `ARM_TENANT_ID`, `ARM_SUBSCRIPTION_ID` | `providerSpec.resourceGroup` |
| Alibaba | `ALICLOUD_ACCESS_KEY`, `ALICLOUD_SECRET_KEY`, `ALICLOUD_REGION` | 없음 |
| OCI | `TF_VAR_tenancy_ocid`, `TF_VAR_user_ocid`, `TF_VAR_fingerprint`, `TF_VAR_region`, `TF_VAR_private_key` 또는 `TF_VAR_private_key_path` | `providerSpec.compartmentId`, `spec.osImage` |
| DigitalOcean | `DIGITALOCEAN_TOKEN` 또는 `DIGITALOCEAN_ACCESS_TOKEN` | 없음 |

## Private Providers

| Provider | Credential ENV / MANUAL key | 필수 `providerSpec` |
| --- | --- | --- |
| OpenStack | `OS_AUTH_URL`, `OS_USERNAME`, `OS_PASSWORD`, `OS_PROJECT_NAME`, `OS_USER_DOMAIN_NAME`, `OS_PROJECT_DOMAIN_NAME`, `OS_REGION_NAME` | `providerSpec.imageName`, `providerSpec.flavorName`, 그리고 `providerSpec.externalNetworkId` 또는 `providerSpec.floatingIpPool` |
| IBM | `IBMCLOUD_API_KEY`, `IBMCLOUD_REGION` | `providerSpec.zone` |
| Proxmox | `PROXMOX_VE_ENDPOINT`, 그리고 `PROXMOX_VE_API_TOKEN` 또는 `PROXMOX_VE_USERNAME`+`PROXMOX_VE_PASSWORD` | `providerSpec.nodeName` |

## 운영 메모

- `MANUAL` credential은 `CSP_CREDENTIAL_ENCRYPTION_KEY`가 설정된 상태에서 DB에 암호화 저장합니다.
- `ENV` credential은 백엔드 컨테이너 환경변수를 그대로 참조합니다.
- Bruno CLI 테스트 시에는 `VM_CREDENTIALS_JSON` 같은 `process.env` 패턴으로 민감값을 파일 밖에서 주입하는 방식을 권장합니다.
- 실제 `Pulumi` 실행과 `delete/retry`는 같은 `credentialId`를 다시 사용합니다.
- `IBM`의 `providerSpec.zone`은 region이 아니라 zone입니다(예: `us-south-1`). 계정마다 활성 zone이 달라 region에서 유도하지 않습니다.
- `IBM`은 사전 컴파일된 Pulumi 플러그인이 없습니다. `terraform-provider` 베이스가 OpenTofu provider를 붙이므로 프로그램에 `packages` 선언과 `sdks/` 스키마가 함께 필요합니다. 이미지 빌드 때 만들어 두고 workDir로 복사합니다.
- `Proxmox`의 `providerSpec.datastoreId`(기본 `local-lvm`), `providerSpec.snippetDatastoreId`(기본 `local`), `providerSpec.networkBridge`(기본 `vmbr0`)는 생략하면 기본값을 씁니다.
- `Proxmox`는 인스턴스 타입이 없습니다. `masterInstanceType`을 `"코어-메모리MiB"` 형식(예: `4-8192`)으로 받습니다.
- `Proxmox`는 API 토큰과 username/password가 배타적입니다. 둘 다 넘기면 provider가 거부합니다.
- `Proxmox`는 **API 토큰만으로는 부족합니다.** cloud-init user-data는 `snippets` content type인데, Proxmox API의 업로드 엔드포인트는 `content`를 `iso`, `vztmpl`, `import`로만 받습니다([bugzilla #2208](https://lists.proxmox.com/pipermail/pve-devel/2022-April/052548.html) 미해결). provider가 SSH로 파일을 밀어 넣습니다. SSH 자격증명이 없으면 VM은 만들어지고 user-data 단계에서 실패합니다.
- `Proxmox`의 `PROXMOX_VE_SSH_USERNAME`은 생략하면 `root`입니다. API 토큰 인증에서는 provider가 SSH 사용자를 상속하지 못해 명시적 기본값이 필요합니다. 전용 계정을 쓰면 반드시 지정합니다 — [proxmox-setup.md](../operations/proxmox-setup.md) 참고.
- `Proxmox`의 스니펫 업로드는 기본 `sftp` 모드라 sudo가 필요 없습니다. SFTP subsystem이 꺼진 호스트에서만 `providerSpec.snippetUploadMode=stream`으로 바꿉니다.
- `Proxmox`에서 PVE API가 알려주는 노드 IP가 백엔드에서 닿지 않으면 `PROXMOX_VE_SSH_NODE_ADDRESS_SOURCE=dns` 또는 `PROXMOX_VE_SSH_NODES=[{"name":"pve1","address":"10.0.0.11"}]`로 지정합니다.
- `Proxmox`는 cloud-init snippet 업로드가 가능한 datastore 설정을 권장합니다.
- `OCI`는 image OCID를 리전마다 따로 발급하고 이름으로 찾는 안정된 필터가 없습니다. `osImage`를 필수로 받아 `pulumi preview` 전에 실패시킵니다.
- `Azure`의 `osImage`는 단일 ID가 아니라 `publisher:offer:sku:version` 4단 좌표입니다. 생략하면 `Canonical:ubuntu-24_04-lts:server:latest`를 씁니다.
