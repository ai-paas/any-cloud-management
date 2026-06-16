# CSP Credential API

`CSP Credential`은 VM 기반 클러스터 생성 전에 미리 등록해두는 클라우드 인증 정보입니다. VM Cluster 생성 요청에서 secret을 반복 입력하지 않도록 하고, 저장형 `MANUAL` 자격증명과 애플리케이션 환경변수를 참조하는 `ENV` 자격증명을 구분합니다. VM Cluster는 `credentialId`로 연결하며, Pulumi 실행/재시도/삭제 시 동일 credential을 다시 사용합니다.

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

## sourceType

- `MANUAL`
  - credential key/value를 암호화해서 `aipaas.csp_credential` 테이블에 저장합니다.
  - `CSP_CREDENTIAL_ENCRYPTION_KEY` 환경변수가 필요합니다.
- `ENV`
  - 앱 컨테이너 환경변수에 이미 주입된 credential을 참조합니다.
  - 별도 secret payload는 저장하지 않습니다.

## Bruno 테스트 패턴

Bruno에서는 두 가지 방식으로 `MANUAL` credential 생성 테스트를 할 수 있습니다.

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
- `credentialId`가 없으면 애플리케이션 환경변수 기반으로 실행합니다.
- `credentialId`가 있으면 해당 credential이 Pulumi 실행 시점의 환경변수 override로 적용됩니다.

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
