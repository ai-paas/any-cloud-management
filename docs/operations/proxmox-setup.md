# Proxmox VE 초기 설정

Proxmox VE 를 프로비저닝 대상으로 붙이기 위한 최소 설정입니다. API 토큰 하나와 기존 `root` SSH 키
하나로 끝나며, 전용 SSH 계정을 만들지 않습니다.

## 필요한 것

| 항목 | 용도 | 없으면 |
|---|---|---|
| API 토큰 | VM, 디스크, 네트워크 생성 | 프로비저닝이 시작되지 않습니다 |
| SSH 키 (`root`) | cloud-init 스니펫 업로드 | VM 은 뜨고 user-data 단계에서 실패합니다 |
| datastore content type | 이미지 다운로드, 스니펫 저장 | 각각 다른 지점에서 거부됩니다 |

### SSH 가 필요한 이유

cloud-init user-data 는 `snippets` content type 으로 저장되는데, Proxmox API 의 업로드 엔드포인트는
`content` 를 `iso`, `vztmpl`, `import` 로만 받습니다.

```
POST /api2/json/nodes/{node}/storage/{storage}/upload
→ 400 Parameter verification failed. content: upload content type 'snippets' not allowed
```

우회로인 `download-url` 엔드포인트도 같은 제한을 받습니다. Proxmox 측에서 몇 년째 열려 있는 요청
([bugzilla #2208](https://lists.proxmox.com/pipermail/pve-devel/2022-April/052548.html))이라 당분간
바뀌지 않습니다. Pulumi provider 는 파일을 SSH 로 밀어 넣습니다. **API 토큰만으로는 부족합니다.**

## 1. API 토큰 발급

PVE 노드에서 `root` 로 실행합니다.

```bash
pveum user add anycloud@pve --comment "AI-PaaS provisioning"

pveum role add AnycloudProvision -privs \
  "VM.Allocate,VM.Clone,VM.Config.CDROM,VM.Config.CPU,VM.Config.Cloudinit,\
VM.Config.Disk,VM.Config.HWType,VM.Config.Memory,VM.Config.Network,\
VM.Config.Options,VM.Monitor,VM.PowerMgmt,VM.Audit,\
Datastore.AllocateSpace,Datastore.AllocateTemplate,Datastore.Audit,\
Sys.Audit,SDN.Use"

pveum acl modify / --user anycloud@pve --role AnycloudProvision
pveum user token add anycloud@pve provisioning --privsep 0
```

`@pve` 는 Proxmox 내장 realm 이라 리눅스 시스템 계정을 만들지 않습니다.

`--privsep 0` 이 빠지면 토큰 권한이 사용자 권한과 교집합이 되는데, 토큰에 ACL 을 따로 주지 않으면
권한이 0 이 됩니다. 권한 오류가 나면 이 옵션부터 확인합니다.

권한 범위를 좁히려면 `/` 대신 `/vms`, `/storage/local-lvm`, `/storage/local`, `/nodes/{node}` 로
나눠 부여합니다. `SDN.Use` 는 네트워크 브리지 연결에 필요합니다.

### 토큰 값 형식

출력의 `value` 는 **발급 시점에 한 번만** 표시됩니다.

```
full-tokenid  anycloud@pve!provisioning
value         12345678-1234-1234-1234-123456789abc
```

`PROXMOX_VE_API_TOKEN` 에는 두 값을 `=` 로 이은 한 줄을 넣습니다.

```
anycloud@pve!provisioning=12345678-1234-1234-1234-123456789abc
```

## 2. datastore content type 활성화

```bash
pvesm set local --content iso,vztmpl,backup,snippets,import
```

| content | 쓰는 곳 |
|---|---|
| `snippets` | cloud-init user-data |
| `import` | Ubuntu cloud 이미지 다운로드 |

## 3. SSH 키 등록

기존 `root` 계정을 씁니다. 새 계정을 만들지 않습니다.

```bash
# 개발 머신
ssh-keygen -t ed25519 -N "" -C "anycloud-provisioning" -f ~/.ssh/anycloud-pve
ssh-copy-id -i ~/.ssh/anycloud-pve.pub root@pve1
```

`ssh-copy-id` 가 막히면 직접 붙입니다.

```bash
cat ~/.ssh/anycloud-pve.pub | ssh root@pve1 'cat >> /root/.ssh/authorized_keys'
```

**개인키에 passphrase 가 있으면 안 됩니다.** provider 가 PEM 을 그대로 읽어 복호화하지 못합니다.
위 명령의 `-N ""` 가 그 이유입니다.

## 4. 자격증명 등록

```bash
KEY=$(awk '{printf "%s\\n", $0}' ~/.ssh/anycloud-pve)

curl -X POST http://localhost:8888/v1/credentials \
  -H 'Content-Type: application/json' -d @- <<JSON
{
  "name": "proxmox-lab-01",
  "provider": "Proxmox",
  "credentialType": "MANUAL",
  "environment": {
    "PROXMOX_VE_ENDPOINT": "https://pve1.example.com:8006/",
    "PROXMOX_VE_API_TOKEN": "anycloud@pve!provisioning=12345678-...",
    "PROXMOX_VE_INSECURE": "true",
    "PROXMOX_VE_SSH_PRIVATE_KEY": "$KEY"
  }
}
JSON
```

`PROXMOX_VE_SSH_USERNAME` 은 생략합니다. 기본값이 `root` 입니다. API 토큰 인증에서는 provider 가
SSH 사용자를 API 사용자에서 상속하지 못하기 때문에 명시적 기본값을 둡니다.

### 환경 변수

| 키 | 필수 | 기본값 | 비고 |
|---|---|---|---|
| `PROXMOX_VE_ENDPOINT` | 예 | | 끝에 `/`, `/api2/json` 은 넣지 않습니다 |
| `PROXMOX_VE_API_TOKEN` | 예 | | `full-tokenid=value`. username/password 와 배타적입니다 |
| `PROXMOX_VE_SSH_PRIVATE_KEY` | 예 | | passphrase 없는 PEM. `PROXMOX_VE_SSH_PASSWORD` 로 대체 가능합니다 |
| `PROXMOX_VE_INSECURE` | 아니오 | `false` | 자체 서명 인증서면 `true` |
| `PROXMOX_VE_SSH_USERNAME` | 아니오 | `root` | |
| `PROXMOX_VE_SSH_NODE_ADDRESS_SOURCE` | 아니오 | `api` | 아래 참고 |
| `PROXMOX_VE_SSH_NODES` | 아니오 | | 노드별 주소 직접 지정 |

API 토큰과 `PROXMOX_VE_USERNAME`+`PROXMOX_VE_PASSWORD` 는 배타적입니다. 둘 다 넘기면 provider 가
거부하므로, 토큰이 있으면 username/password 는 무시합니다.

## 5. 프로비저닝 요청

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

`providerSpec` 의 나머지는 기본값이 있어 생략했습니다.

| 키 | 기본값 |
|---|---|
| `datastoreId` | `local-lvm` |
| `snippetDatastoreId` | `local` |
| `networkBridge` | `vmbr0` |

Proxmox 는 하이퍼바이저라 VPC, 서브넷, 보안그룹을 만들지 않고 기존 브리지에 붙습니다.
`spec.network.vpcCidr` 은 쓰이지 않습니다.

## 사전 확인

프로비저닝 전에 세 가지를 확인합니다.

```bash
# 1. 토큰이 API 를 통과하는가
curl -k -H 'Authorization: PVEAPIToken=anycloud@pve!provisioning=<value>' \
  https://pve1:8006/api2/json/version

# 2. datastore 에 snippets 가 켜졌는가
curl -k -H 'Authorization: PVEAPIToken=...' \
  https://pve1:8006/api2/json/nodes/pve1/storage | grep -o 'snippets'

# 3. SSH 로 스니펫 디렉터리에 쓸 수 있는가
ssh -i ~/.ssh/anycloud-pve root@pve1 \
  'touch /var/lib/vz/snippets/.probe && rm /var/lib/vz/snippets/.probe && echo OK'
```

3번을 따로 확인하는 이유는 앞서 설명한 API 제약입니다. 1, 2번이 통과해도 3번이 막히면 VM 은
정상적으로 뜨고 cloud-init 만 실행되지 않아 원인을 찾기 어렵습니다.

## 문제 해결

### 노드 IP 에 SSH 가 닿지 않을 때

provider 는 기본적으로 PVE API 가 알려주는 노드 IP 로 SSH 합니다. 다중 서브넷 환경에서는 그 주소가
백엔드에서 닿지 않을 수 있습니다.

노드 주소를 직접 지정합니다.

```json
"PROXMOX_VE_SSH_NODES": "[{\"name\":\"pve1\",\"address\":\"10.0.0.11\"}]"
```

또는 로컬 DNS 로 풀게 합니다.

```json
"PROXMOX_VE_SSH_NODE_ADDRESS_SOURCE": "dns"
```

`PROXMOX_VE_SSH_NODES` 가 JSON 배열이 아니면 자격증명 등록 단계에서 실패합니다. 값이 깨진 채로
넘어가면 SSH 가 엉뚱한 주소로 가기 때문입니다.

### 증상별 원인

| 증상 | 확인할 것 |
|---|---|
| 프로비저닝이 시작되지 않음 | 토큰 형식(`full-tokenid=value`), `--privsep 0` |
| `content type 'snippets' not allowed` | datastore 에 `snippets` 활성화 |
| VM 은 뜨는데 kubeadm 이 없음 | cloud-init 미실행 — 사전 확인 3번 |
| SSH timeout | 노드 주소 — 위 항목 |
| TLS 오류 | `PROXMOX_VE_INSECURE=true` |

## 보안 고려

`root` SSH 를 쓰면 API 토큰을 좁게 발급한 이점이 상쇄됩니다. 스니펫 파일 하나를 올리기 위해
관리자 계정을 내주는 구조입니다.

권한을 좁히려면 전용 계정을 만들고 스니펫 디렉터리 쓰기 권한만 부여합니다.

```bash
useradd -m -s /bin/bash anycloud-ssh
mkdir -p /home/anycloud-ssh/.ssh
cat ~/.ssh/anycloud-pve.pub >> /home/anycloud-ssh/.ssh/authorized_keys
chmod 700 /home/anycloud-ssh/.ssh && chmod 600 /home/anycloud-ssh/.ssh/authorized_keys
chown -R anycloud-ssh: /home/anycloud-ssh/.ssh

chgrp -R anycloud-ssh /var/lib/vz/snippets
chmod -R g+w /var/lib/vz/snippets
```

`PROXMOX_VE_SSH_USERNAME=anycloud-ssh` 를 추가하면 됩니다. sudo 없이 동작하는지는 사전 확인 3번을
이 계정으로 다시 실행해 판단합니다.

NFS 나 CIFS 를 snippets datastore 로 붙여 PVE 호스트 SSH 없이 해당 스토리지에 직접 쓰는 방법도
있지만, 인프라 구성이 달라지므로 별도로 검토합니다.

## 관련 문서

- [provider-credential-matrix.md](../api/provider-credential-matrix.md) — CSP 별 자격증명과 필수 설정
- [v1-reference.md](../api/v1-reference.md) — VM 생성 요청 형태
- [pulumi-yaml-migration.md](../architecture/pulumi/pulumi-yaml-migration.md) — provider 별 emitter 설계
