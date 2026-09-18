# Proxmox VE 초기 설정

Proxmox VE 를 프로비저닝 대상으로 붙이기 위한 설정입니다. 준비물은 **API 토큰 하나**입니다.
하이퍼바이저 호스트에 SSH 계정을 만들거나 키를 등록할 일은 없습니다.

## 준비물

| 항목 | 용도 | 없으면 |
|---|---|---|
| API 토큰 (ID + 시크릿) | VM, 디스크, cloud-init 디스크 생성 | 프로비저닝이 시작되지 않습니다 |
| `import` content type | Ubuntu cloud 이미지 다운로드 | 이미지 다운로드 단계에서 거부됩니다 |

## 전제조건

### PVE 8.2.8 이상

`import` content type 이 `libpve-storage-perl` 8.2.8(2024-11-18)에서 들어왔습니다. 그 이전
버전에는 `pvesm set --content import` 옵션 자체가 없습니다. `download-url` API 가 받는 값이
`iso`, `vztmpl`, `import` 뿐이라 우회할 방법도 없습니다.

```bash
pveversion            # pve-manager/8.2.x 이상
```

### PVE 호스트의 인터넷 접속

이미지를 `cloud-images.ubuntu.com` 에서 내려받습니다. 폐쇄망이면 `spec.osImage` 에 내부 미러
URL 을 넣습니다. 노드(VM)도 apt 저장소와 컨테이너 레지스트리로 나가야 합니다 — 부트스트랩이
노드에서 kubeadm 을 설치하기 때문입니다.

### 디스크 스토리지 이름

설치할 때 고른 파일시스템에 따라 이름이 갈립니다.

| 설치 유형 | 디스크 스토리지 | 요청에 넣을 값 |
|---|---|---|
| ext4 / xfs (LVM) | `local-lvm` | 기본값이라 생략 가능 |
| ZFS | `local-zfs` | `providerSpec.datastoreId` 에 **직접 지정** |

`datastoreId` 기본값이 `local-lvm` 이라, ZFS 로 설치한 호스트에서 생략하면 스토리지를 찾지 못합니다.

```bash
pvesm status          # 실제 이름 확인
```

## kubeadm 은 노드가 직접 설치합니다

다른 CSP 는 인스턴스 생성 API 에 user-data 를 실어 보내고, 메타데이터 서비스가 그것을 보관합니다.
Proxmox 는 하이퍼바이저라 메타데이터 서비스가 없고, cloud-init 설정이 호스트의 파일입니다. API 는
그 파일 쓰기를 거부합니다.

```
POST /api2/json/nodes/{node}/storage/{storage}/upload
→ 400 Parameter verification failed. content: upload content type 'snippets' not allowed
```

`content` 로 받는 값은 `iso`, `vztmpl`, `import` 뿐이고, 우회로인 `download-url` 도 같은 제한을
받습니다. Proxmox 측에서 몇 년째 열려 있는 요청
([pve-devel #2208](https://lists.proxmox.com/pipermail/pve-devel/2022-April/052548.html))이라
당분간 바뀌지 않습니다.

그래서 **user-data 를 쓰지 않습니다.** VM 에는 API 로 공개키만 넣고, 패키지 설치는 부트스트랩이
노드에 SSH 로 접속해 수행합니다. 다른 CSP 가 cloud-init 으로 깔던 것과 같은 스크립트입니다.

```
다른 CSP                          Proxmox
  생성 API ┬ 스펙                   생성 API ── 스펙 + 공개키
          └ user-data              부트스트랩 ── 노드 SSH 로 패키지 설치
  부트스트랩 ── 설치 완료 대기        부트스트랩 ── 이어서 kubeadm
```

결과적으로 프로비저닝이 필요로 하는 접근 권한은 API 토큰 하나로 끝납니다.

## 1. API 토큰 발급

PVE 노드에서 `root` 로 실행합니다.

빠르게 붙일 때는 내장 역할 네 개를 씁니다.

```bash
pveum user add anycloud@pve --comment "AI-PaaS provisioning"

pveum acl modify /        --user anycloud@pve --role PVEVMAdmin
pveum acl modify /        --user anycloud@pve --role PVEAuditor
pveum acl modify /storage --user anycloud@pve --role PVEDatastoreAdmin
pveum acl modify /sdn     --user anycloud@pve --role PVESDNUser

pveum user token add anycloud@pve provisioning --privsep 0
```

`pveum acl modify` 는 기존 항목을 덮지 않고 더합니다. 같은 경로에 역할을 여러 개 붙일 수 있습니다.

`@pve` 는 Proxmox 내장 realm 이라 리눅스 시스템 계정을 만들지 않습니다.

`--privsep 0` 이 빠지면 토큰 권한이 사용자 권한과 교집합이 되는데, 토큰에 ACL 을 따로 주지 않으면
권한이 0 이 됩니다. 권한 오류가 나면 이 옵션부터 확인합니다.

### 어떤 권한이 어디에 쓰이는지

세 가지가 빠지기 쉽습니다. `PVEVMAdmin` 하나만 주면 이미지 다운로드와 브리지 연결에서 막힙니다.

| 동작 | 요구 권한 | 담긴 역할 |
|---|---|---|
| VM 생성, 설정, 전원, 삭제 | `VM.Allocate`, `VM.Config.*`, `VM.PowerMgmt` | PVEVMAdmin |
| 노드 IP 조회 (QEMU agent) | `VM.GuestAgent.Audit` | PVEVMAdmin |
| 디스크 생성 | `Datastore.AllocateSpace` | PVEDatastoreAdmin |
| **이미지 다운로드** | `Datastore.AllocateTemplate` | PVEDatastoreAdmin |
| **이미지 다운로드** | `Sys.Audit` 또는 `Sys.Modify` (`/`), 혹은 `Sys.AccessNetwork` (`/nodes`) | PVEAuditor |
| **브리지 연결** | `SDN.Use` | PVESDNUser |

`PVEDatastoreUser` 로는 부족합니다. `Datastore.AllocateSpace` 와 `Datastore.Audit` 둘뿐이라
`Datastore.AllocateTemplate` 이 없습니다.

`Sys.AccessNetwork` 는 어떤 내장 역할에도 들어 있지 않습니다. PVE 가 이 권한을 `root` 등급으로
분류하는데, 내장 역할은 `admin`, `user`, `audit` 등급만 모아 만들기 때문입니다. `PVEAuditor` 의
`Sys.Audit` 으로 대신 충족시킵니다.

### 권한 범위 좁히기

`/` 전체가 부담스러우면 커스텀 역할 하나를 만들어 필요한 경로에만 붙입니다.

```bash
pveum role add AnycloudProvision -privs \
  "VM.Allocate,VM.Audit,VM.Clone,VM.Config.CDROM,VM.Config.CPU,VM.Config.Cloudinit,\
VM.Config.Disk,VM.Config.HWType,VM.Config.Memory,VM.Config.Network,\
VM.Config.Options,VM.PowerMgmt,VM.GuestAgent.Audit,\
Datastore.AllocateSpace,Datastore.AllocateTemplate,Datastore.Audit,\
Sys.Audit,Sys.AccessNetwork,SDN.Use"

pveum acl modify /vms                  --user anycloud@pve --role AnycloudProvision
pveum acl modify /storage/local-lvm    --user anycloud@pve --role AnycloudProvision
pveum acl modify /storage/local        --user anycloud@pve --role AnycloudProvision
pveum acl modify /nodes/pve1           --user anycloud@pve --role AnycloudProvision
pveum acl modify /sdn/zones            --user anycloud@pve --role AnycloudProvision
```

이 경로 구성에서는 `/nodes/pve1` 의 `Sys.AccessNetwork` 가 다운로드 조건을 충족하므로 `/` 에
권한을 줄 필요가 없습니다.

`VM.Monitor` 는 현재 PVE 에 없는 권한입니다. 넣으면 역할 생성 명령 자체가 거부됩니다.

노드나 datastore 를 여러 개 쓰면 각각 추가합니다. 범위를 좁히면 권한 오류가 잦아지므로,
처음에는 내장 역할로 동작을 확인한 뒤 좁히는 편이 진단이 빠릅니다.

### 토큰 값 형식

출력의 `value` 는 **발급 시점에 한 번만** 표시됩니다.

```
full-tokenid  anycloud@pve!provisioning
value         12345678-1234-1234-1234-123456789abc
```

두 값을 그대로 나눠 넣습니다. `=` 로 잇는 것은 백엔드가 합니다.

| 화면 | 환경 변수 | 값 |
|---|---|---|
| 토큰 ID | `PROXMOX_VE_API_TOKEN_ID` | `anycloud@pve!provisioning` |
| 토큰 시크릿 | `PROXMOX_VE_API_TOKEN_SECRET` | `12345678-1234-1234-1234-123456789abc` |

토큰에 만료일을 지정했다면 그날 이후 프로비저닝이 401 로 실패합니다. 저장하는 값이 아니므로
백엔드는 만료를 미리 알려주지 못합니다.

## 2. datastore content type 활성화

내려받은 cloud 이미지를 두려면 `import` 가 켜져 있어야 합니다.

```bash
pvesm set local --content iso,vztmpl,backup,import
```

datastore 가 둘로 나뉘는 이유는 종류가 다르기 때문입니다.

| 기본값 | 종류 | 담는 것 |
|---|---|---|
| `local-lvm` (ZFS 설치면 `local-zfs`) | 블록 스토리지 | VM 디스크, cloud-init 디스크 |
| `local` | 디렉터리 | 내려받은 cloud 이미지 (`import`) |

블록 스토리지는 `import` content 를 받지 않아 한쪽에 몰 수 없습니다. 반대로 `local` 은 디렉터리
스토리지라 디스크를 만들지 못합니다.

## 3. 자격증명 등록

```bash
curl -X POST http://localhost:8888/v1/credentials \
  -H 'Content-Type: application/json' -d @- <<'JSON'
{
  "name": "proxmox-lab-01",
  "provider": "Proxmox",
  "credentialType": "MANUAL",
  "environment": {
    "PROXMOX_VE_ENDPOINT": "https://pve1.example.com:8006/",
    "PROXMOX_VE_API_TOKEN_ID": "anycloud@pve!provisioning",
    "PROXMOX_VE_API_TOKEN_SECRET": "12345678-1234-1234-1234-123456789abc",
    "PROXMOX_VE_INSECURE": "true"
  }
}
JSON
```

### 환경 변수

| 키 | 필수 | 기본값 | 비고 |
|---|---|---|---|
| `PROXMOX_VE_ENDPOINT` | 예 | | 끝에 `/`. `/api2/json` 은 넣지 않습니다 |
| `PROXMOX_VE_API_TOKEN_ID` | 예 | | `user@realm!tokenname` |
| `PROXMOX_VE_API_TOKEN_SECRET` | 예 | | 발급 시점에 한 번만 표시됩니다 |
| `PROXMOX_VE_INSECURE` | 아니오 | `false` | 자체 서명 인증서면 `true` |

인증은 토큰만 씁니다. username/password 경로는 없습니다.

## 4. 프로비저닝 요청

```json
POST /v1/vms
{
  "vmGroupName": "demo-proxmox-01",
  "provider": "proxmox",
  "region": "pve1",
  "credentialId": "...",
  "spec": {
    "workerCount": 2,
    "masterInstanceType": "4-8192",
    "workerInstanceType": "2-4096",
    "rootDiskSizeGb": 50
  },
  "providerSpec": { "nodeName": "pve1" }
}
```

Proxmox 는 인스턴스 타입이 없어 `masterInstanceType` 을 `"코어-메모리MiB"` 형식으로 받습니다.
`4-8192` 는 4 코어, 8GiB 입니다.

`providerSpec` 에서 `nodeName` 만 필수입니다. 클러스터를 구성했더라도 어느 노드에 올릴지
지정해야 합니다. 이 문서의 `pve1` 은 예시일 뿐이고, 실제 값은 설치할 때 정한 호스트명입니다
(`pvesh get /nodes --output-format json` 또는 웹 UI 좌측 트리에서 확인).

| 키 | 기본값 | 설명 |
|---|---|---|
| `nodeName` | 없음 (필수) | VM 을 올릴 PVE 노드 |
| `datastoreId` | `local-lvm` | 디스크를 만들 블록 스토리지 |
| `imageDatastoreId` | `local` | 이미지를 내려받을 디렉터리 스토리지 |
| `networkBridge` | `vmbr0` | 붙일 브리지 |

Proxmox 는 하이퍼바이저라 VPC, 서브넷, 보안그룹을 만들지 않고 기존 브리지에 붙습니다.
`spec.network.vpcCidr` 은 쓰이지 않습니다.

## 사전 확인

```bash
# 0. 버전과 스토리지 이름
pveversion
pvesm status

# 1. 토큰이 API 를 통과하는가
curl -k -H 'Authorization: PVEAPIToken=anycloud@pve!provisioning=<value>' \
  https://pve1:8006/api2/json/version

# 2. datastore 에 import 가 켜졌는가
curl -k -H 'Authorization: PVEAPIToken=...' \
  https://pve1:8006/api2/json/nodes/pve1/storage | grep -o 'import'

# 3. 권한이 다 붙었는가 — 아래 세 개가 보여야 합니다
pveum user permissions anycloud@pve --path /storage/local   # Datastore.AllocateTemplate
pveum user permissions anycloud@pve --path /                # Sys.Audit
pveum user permissions anycloud@pve --path /sdn             # SDN.Use
```

두 가지가 통과하면 프로비저닝을 시작할 수 있습니다. VM 이 뜬 뒤 부트스트랩이 노드에 SSH 로
접속하므로, 백엔드에서 VM 의 브리지 대역으로 22 번 포트가 닿아야 합니다.

## 문제 해결

| 증상 | 확인할 것 |
|---|---|
| 프로비저닝이 시작되지 않음 | 토큰 ID 형식(`user@realm!name`), `--privsep 0`, 토큰 만료일 |
| `content type 'import' not allowed` | datastore 에 `import` 활성화 (2번) |
| 이미지 다운로드 403 | `Datastore.AllocateTemplate`, `Sys.Audit` — 사전 확인 3번 |
| VM 생성이 브리지에서 403 | `SDN.Use` — 사전 확인 3번 |
| 노드 IP 가 비어 나옴 | `VM.GuestAgent.Audit` 또는 이미지의 `qemu-guest-agent` |
| 이미지 다운로드 실패 | `imageDatastoreId` 가 디렉터리 스토리지인지, 호스트가 이미지 URL 로 나가는지 |
| 디스크 생성 실패 | `datastoreId` 가 블록 스토리지인지 — ZFS 설치면 `local-zfs` |
| `pvesm set` 에 `import` 옵션이 없음 | PVE 8.2.8 미만 — 업그레이드 외에 방법이 없습니다 |
| VM 은 뜨는데 SSH 접속 실패 | 아래 참고 |
| BOOTSTRAP 단계에서 패키지 설치 실패 | VM 에서 외부 인터넷이 닿는지 (apt, 레지스트리) |
| TLS 오류 | `PROXMOX_VE_INSECURE=true` |

### VM 에 SSH 접속이 안 될 때

공개키는 `initialization.userAccount` 로 주입되며 `spec.sshUser`(기본 `ubuntu`)를 사용자로 씁니다.
cloud 이미지의 기본 사용자와 다르면 접속이 거부됩니다. Ubuntu cloud image 는 `ubuntu` 입니다.

주소는 QEMU guest agent 가 보고합니다. agent 가 응답하지 않으면 노드 IP 가 비어 나오므로,
이미지에 `qemu-guest-agent` 가 들어 있는지 확인합니다.

## 관련 문서

- [provider-credential-matrix.md](../api/provider-credential-matrix.md) — CSP 별 자격증명과 필수 설정
- [v1-reference.md](../api/v1-reference.md) — VM 생성 요청 형태
- [pulumi-yaml-migration.md](../architecture/pulumi/pulumi-yaml-migration.md) — provider 별 emitter 설계
