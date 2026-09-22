# Provider Credential Matrix

이 문서는 `VM Cluster` 생성 시 각 Provider에서 필요한 자격증명과 필수 config를 빠르게 확인하기 위한 표입니다.

## Public Providers

| Provider | Credential ENV / MANUAL key | 필수 `providerSpec` |
| --- | --- | --- |
| AWS | `AWS_ACCESS_KEY_ID`, `AWS_SECRET_ACCESS_KEY`, `AWS_REGION` | 없음 |
| GCP | `GOOGLE_APPLICATION_CREDENTIALS` 또는 `GOOGLE_CREDENTIALS` | `providerSpec.project` |

| Alibaba | `ALICLOUD_ACCESS_KEY`, `ALICLOUD_SECRET_KEY`, `ALICLOUD_REGION` | 없음 |
| OCI | `TF_VAR_tenancy_ocid`, `TF_VAR_user_ocid`, `TF_VAR_fingerprint`, `TF_VAR_region`, `TF_VAR_private_key` 또는 `TF_VAR_private_key_path` | `providerSpec.compartmentId`, `spec.osImage` |

## Private Providers

| Provider | Credential ENV / MANUAL key | 필수 `providerSpec` |
| --- | --- | --- |
| OpenStack | `OS_AUTH_URL`, `OS_USERNAME`, `OS_PASSWORD`, `OS_PROJECT_NAME`, `OS_USER_DOMAIN_NAME`, `OS_PROJECT_DOMAIN_NAME`, `OS_REGION_NAME` | `providerSpec.externalNetworkId`, `providerSpec.floatingIpPool` (둘 다), `providerSpec.flavorName` |
| IBM | `IBMCLOUD_API_KEY`, `IBMCLOUD_REGION` | `providerSpec.zone` |
| Proxmox | `PROXMOX_VE_ENDPOINT`, `PROXMOX_VE_API_TOKEN_ID`, `PROXMOX_VE_API_TOKEN_SECRET` | `providerSpec.nodeName` |

## 운영 메모

- `MANUAL` credential은 `CSP_CREDENTIAL_ENCRYPTION_KEY`가 설정된 상태에서 DB에 암호화 저장합니다.
- `ENV` credential은 백엔드 컨테이너 환경변수를 그대로 참조합니다.
- Bruno CLI 테스트 시에는 `VM_CREDENTIALS_JSON` 같은 `process.env` 패턴으로 민감값을 파일 밖에서 주입하는 방식을 권장합니다.
- 실제 `Pulumi` 실행과 `delete/retry`는 같은 `credentialId`를 다시 사용합니다.
- `IBM`의 `providerSpec.zone`은 region이 아니라 zone입니다(예: `us-south-1`). 계정마다 활성 zone이 달라 region에서 유도하지 않습니다.
- `IBM`은 사전 컴파일된 Pulumi 플러그인이 없습니다. `terraform-provider` 베이스가 OpenTofu provider를 붙이므로 프로그램에 `packages` 선언과 `sdks/` 스키마가 함께 필요합니다. 이미지 빌드 때 만들어 두고 workDir로 복사합니다.
- `Proxmox`의 `providerSpec.datastoreId`(기본 `local-lvm`), `providerSpec.imageDatastoreId`(기본 `local`), `providerSpec.networkBridge`(기본 `vmbr0`)는 생략하면 기본값을 씁니다. 디스크는 블록 스토리지, 내려받은 이미지는 `import` content를 받는 디렉터리 스토리지로 가기 때문에 둘을 같은 곳에 둘 수 없습니다.
- `Proxmox`는 **PVE 8.2.8 이상**이 필요합니다. 이미지 다운로드가 쓰는 `import` content type이 그 버전에서 들어왔고, `download-url` API는 `iso`, `vztmpl`, `import`만 받아 우회할 방법이 없습니다.
- `Proxmox`의 `providerSpec.datastoreId` 기본값 `local-lvm`은 ext4/xfs 설치 기준입니다. ZFS로 설치한 호스트는 `local-zfs`라 직접 지정해야 합니다.
- `Proxmox`의 `providerSpec.nodeName`은 설치할 때 정한 호스트명입니다. 문서 예시의 `pve1`은 기본값이 아닙니다.
- `Proxmox`는 인스턴스 타입이 없습니다. `masterInstanceType`을 `"코어-메모리MiB"` 형식(예: `4-8192`)으로 받습니다.
- `Proxmox`는 API 토큰으로만 인증합니다. PVE 토큰 생성 화면이 Token ID와 Secret을 따로 보여주므로 두 칸으로 받고, provider가 받는 `user@realm!name=uuid` 한 줄은 백엔드가 만듭니다. 토큰 만료일을 지정했다면 그 이후 첫 API 호출이 401로 실패합니다.
- `Proxmox`는 cloud-init user-data를 쓰지 않습니다. user-data는 `snippets` content type인데 Proxmox API의 업로드 엔드포인트가 `content`를 `iso`, `vztmpl`, `import`로만 받습니다([bugzilla #2208](https://lists.proxmox.com/pipermail/pve-devel/2022-April/052548.html) 미해결). 전달하려면 PVE 호스트 SSH 권한이 필요해지므로, VM에는 API로 공개키만 넣고 패키지 설치는 부트스트랩이 노드 SSH로 수행합니다. 자격증명은 API 토큰만으로 끝납니다.
- `OpenStack`의 `externalNetworkId`와 `floatingIpPool`은 **둘 다** 필요합니다. emitter가 각각 라우터 게이트웨이와 플로팅 IP 할당에 쓰므로 하나만 넘기면 즉시 실패합니다.
- `OpenStack`의 `imageName`은 생략하면 `spec.osImage`, 그다음 기본 이미지 순으로 떨어집니다. `flavorName`은 생략하면 `spec.workerInstanceType`을 쓰는데 OpenStack은 기본 인스턴스 타입이 없어 둘 다 비우면 플레이버가 정해지지 않습니다.
- `OCI`는 image OCID를 리전마다 따로 발급하고 이름으로 찾는 안정된 필터가 없습니다. `osImage`를 필수로 받아 `pulumi preview` 전에 실패시킵니다.
- `OCI`의 Flex 셰이프(`VM.Standard.E4.Flex` 등)는 크기가 셰이프 이름에 없습니다. `shapeConfig`가 빠지면 `400 InvalidParameter`로 거부하므로, 인스턴스 타입 뒤에 `:ocpus:메모리GB`를 붙여 넘깁니다(예: `VM.Standard.E5.Flex:4:32`). 생략하면 2 ocpu / 16 GB입니다. 고정 셰이프에는 붙이지 않습니다.
