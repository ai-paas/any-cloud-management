# CSP Credential API

`CSP Credential`은 VM 그룹 생성 전에 미리 등록해두는 클라우드 인증 정보입니다. 입력한 키/값은 backend 에서 AES 암호화되어 `aipaas.csp_credential` 테이블에 저장됩니다. VM 그룹은 `credentialId`로 연결하며, Pulumi 실행/재시도/삭제 시 동일 credential을 런타임 복호화하여 재사용합니다.

## 엔드포인트

- `GET /v1/credentials`
- `GET /v1/credentials/{credentialId}`
- `POST /v1/credentials`
- `DELETE /v1/credentials/{credentialId}`

> 전체 v1 API surface 는 [v1-reference.md](./v1-reference.md) 를 참고합니다.

## 성공 응답 형식

CSP Credential API의 성공 응답은 공통 envelope를 사용합니다.

- `success`
- `status`
- `message`
- `data`

## 저장 방식

- credential key/value 를 `CSP_CREDENTIAL_ENCRYPTION_KEY` 로 AES-GCM 암호화 후 `aipaas.csp_credential` 테이블에 저장합니다.
- 과거 `sourceType` 분기 (MANUAL/ENV) 는 제거됐습니다 — 단일 backend ENV 자격증명 위임의 사용 가치가 낮고 multi-account 시나리오를 막아 추상화 비용 대비 효익이 없었습니다. 미래 cloud-native IAM (IRSA / Workload Identity 등) 도입 시 별도 per-provider strategy 추상화로 진화합니다.

## Bruno 테스트 패턴

Bruno에서는 두 가지 방식으로 credential 생성 테스트를 할 수 있습니다.

- `Prompt Variable`
  - 요청 실행 시점에 직접 key/value를 입력합니다.
  - 민감값을 `.bru` 파일에 저장하지 않습니다.
- `Process Environment Variable`
  - `VM_CREDENTIALS_JSON`을 shell 환경변수로 주입합니다.
  - CLI 자동화나 반복 테스트에 적합합니다.

예시는 다음과 같습니다.

```bash
export VM_CREDENTIALS_JSON='{"AWS_ACCESS_KEY_ID":"...","AWS_SECRET_ACCESS_KEY":"..."}'
cd .bruno
bru run "VM Credentials (VM 자격증명)/2a. VM Credential Create From Process Env (환경변수 기반 생성).bru" --env local-aws-provisioning
```

## VM Cluster 연동

- `POST /v1/clusters` (body `source=vm`) 요청에서 `credentialId`를 선택적으로 전달할 수 있습니다.
- `credentialId` 는 필수입니다. 누락 시 400.
- credential 의 키/값이 Pulumi 실행 시점에 복호화되어 environment 로 주입됩니다.

## Provider별 credential / config

Provider별 필수 credential key와 provisioning config는
[provider-credential-matrix.md](./provider-credential-matrix.md)
문서를 기준으로 확인합니다.

지원 Provider:

- `AWS`
- `GCP`
- `Azure`
- `Alibaba`
- `OpenStack`
- `Proxmox`
- `OCI`
- `DigitalOcean`

## 저장 테이블

- `aipaas.csp_credential`
- `aipaas.vm_cluster`
  - `credential_id`
  - `credential_name`
  - `credential_source_type`
