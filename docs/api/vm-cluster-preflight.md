# VM Cluster Preflight API

`VM Cluster Preflight`는 실제 `create` 전에 요청을 미리 점검하는 API입니다. 다음 항목을 검증합니다.

- provider 정규화
- cluster 이름 충돌 확인
- 기본값 적용 결과 확인
- 필수 config 누락 확인
- VM Options 기반 selection 검증
- credential 준비 상태 확인
- 예상 `stackName` 확인

## Endpoint

- `POST /v1/cluster-validations`

응답은 `201 Created` 와 body 의 검증 결과로 구성되며, `links.createCluster` 가 다음 단계 `POST /v1/clusters` 를 가리킵니다.

> 전체 v1 API surface 는 [v1-reference.md](./v1-reference.md) 를 참고합니다.

## 주요 응답 필드

- 최상위 envelope
  - `success`
  - `status`
  - `message`
  - `data`
- `readyToProvision`
- `existingClusterConflict`
- `provider`
- `clusterName`
- `environment`
- `region`
- `stackName`
- `credentialId`
- `credentialName`
- `credentialSourceType`
- `credentialResolved`
- `requiredCredentialKeys`
- `missingCredentialKeys`
- `vmOptionsDiscoveryChecked`
- `vmOptionsDiscoveryReady`
- `vmOptionsDiscoveryMessages`
- `providerReadinessChecked`
- `providerReadinessReady`
- `providerReadinessMessages`
- `e2eChecklistItems`
- `normalizedConfig`
- `appliedDefaults`
- `warnings`
- `errors`
- `warningItems`
- `errorItems`

## 사용 흐름

1. `POST /v1/cluster-validations` 로 검증 결과 자원을 받습니다 (201).
2. `readyToProvision=true` 와 `errors=[]` 를 확인합니다.
3. 필요 시 `missingCredentialKeys`, `warnings`, `normalizedConfig` 를 검토합니다.
4. 응답 `links.createCluster` 를 따라 `POST /v1/clusters` body `{source:"vm", ...}` 를 호출하면 202 + Operation 이 반환됩니다.

생성된 Operation 추적은 `Location` 헤더의 `/v1/operations/{id}` polling 또는
`GET /v1/operations/{id}/events` (SSE) 를 사용합니다.

## 해석 가이드

- `readyToProvision=true`
  - 현재 입력 기준으로 생성 요청을 진행할 수 있는 상태입니다.
- `missingCredentialKeys`가 비어 있지 않음
  - provider 실행에 필요한 credential이 아직 준비되지 않았습니다.
- `appliedDefaults`
  - 요청에 비어 있던 값 중 기본값이 자동 적용된 key 목록입니다.
- `providerReadinessMessages`
  - provider별로 실제 E2E 전에 수동 확인이 필요한 항목을 요약해서 보여줍니다.
- `e2eChecklistItems`
  - credential이 준비된 뒤 실제 생성/삭제 테스트 전에 점검할 체크리스트입니다.
- `errors`
  - 실제 생성 전에 반드시 수정해야 하는 항목입니다.
- `warnings`
  - 생성은 가능할 수 있지만 점검이 필요한 항목입니다.
- `warningItems`, `errorItems`
  - 문자열 배열과 함께 `code`, `message`, `field`를 가진 구조화된 이슈 목록입니다.
