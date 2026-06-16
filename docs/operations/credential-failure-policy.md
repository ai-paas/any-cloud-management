# CSP Credential Failure Policy

CSP 자격증명 (AWS / GCP / Azure / Alibaba / OCI / DigitalOcean / OpenStack / Proxmox) 호출
실패의 분류와 응답 정책. 정책 위반은 (1) reconnaissance 정보 노출, (2) 무의미한 retry로 인한
RabbitMQ DLQ 미도달 / 로그 폭주, (3) 운영자가 transient 와 permanent 를 구분 못 함 등 직접
보안·신뢰성 사고로 이어진다.

## 1. 분류 매트릭스

| 신호 | 분류 | Exception | 응답 status | RabbitMQ retry |
|---|---|---|---|---|
| CSP API 5xx, network timeout, DNS resolve 실패 | Transient | `TransientProvisioningFailure` | 502 UPSTREAM_FAILED | yes (maxAttempts) |
| CSP API 401 / 403 (invalid signature, expired key, IAM denied) | Permanent | `PermanentProvisioningFailure(UPSTREAM_FAILED)` | 502 UPSTREAM_FAILED | no (즉시 DLQ) |
| Credential row 없음 (DB lookup miss) | Not found | `CustomException(NOT_FOUND)` | 404 | n/a (sync only) |
| Credential ID blank 인데 MANUAL 요구 | Bad input | `CustomException(INVALID_INPUT_VALUE)` | 400 | n/a |
| Credential provider != VM provider | Bad input (permanent) | `PermanentProvisioningFailure(INVALID_INPUT_VALUE)` | 400 | no (즉시 DLQ) |
| Encryption key 누락 / sentinel | Startup failure | `IllegalStateException` (fail-fast) | n/a (process exit) | n/a |
| Credential payload corruption (JSON parse 실패) | Internal | `CustomException(RUNTIME_EXCEPTION)` | 500 | yes (corruption 일시일 가능성 — maxAttempts 후 DLQ) |

retry 분기는 `RabbitMqVmClusterWorkflowConfiguration` 의 `ExceptionClassifierRetryPolicy` 가
`ProvisioningException.isTransient()` 를 보고 결정. `PermanentProvisioningFailure` 와
`StateConflictException` 은 `isTransient()=false` 이므로 즉시 DLQ 라우팅.

## 2. CSP 별 stderr 패턴

Pulumi up 실패 시 stderr 를 위 표에 매핑하는 가이드. 패턴 매칭 구현은 향후 PR — 현재는
운영자가 stderr 보고 수동 분류.

| Provider | Permanent (401/403) 패턴 | Transient (5xx/timeout) 패턴 |
|---|---|---|
| AWS | `InvalidAccessKeyId`, `SignatureDoesNotMatch`, `UnauthorizedOperation`, `AccessDenied` | `RequestTimeout`, `ServiceUnavailable`, `ThrottlingException` (재시도 한도 내) |
| GCP | `invalid_grant`, `UNAUTHENTICATED`, `PERMISSION_DENIED` | `INTERNAL`, `UNAVAILABLE`, `DEADLINE_EXCEEDED` |
| Azure | `AuthorizationFailed`, `InvalidAuthenticationToken`, `Forbidden` | `ServiceUnavailable`, `OperationTimedOut`, `TooManyRequests` |
| Alibaba | `InvalidAccessKeyId.NotFound`, `Forbidden.RAM`, `SignatureDoesNotMatch` | `ServiceUnavailable`, `Throttling`, `InternalError` |
| OCI | `NotAuthenticated`, `NotAuthorizedOrNotFound`, `Forbidden` | `ServiceUnavailable`, `RequestTimeout`, `InternalServerError` |
| DigitalOcean | `Unable to authenticate you`, `403 Forbidden` | `internal_server_error`, `service_unavailable` |
| OpenStack | `401 Unauthorized`, `403 Forbidden` | `503 Service Unavailable`, `timeout` |
| Proxmox | `authentication failure`, `permission denied` | `500 Internal Server Error`, connection refused |

## 3. 응답 본문 redaction

`PermanentProvisioningFailure` / `TransientProvisioningFailure` 의 message / detail 에 다음
정보를 절대 포함하지 말 것. 누설 시 reconnaissance 자료가 됨.

- AWS Account ID (12 자리 숫자), IAM ARN (`arn:aws:iam::*`), Access Key ID (`AKIA*` /
  `ASIA*`), Secret Access Key (40+ char base64).
- GCP project ID (full path), service account email, OAuth refresh token.
- Azure subscription ID (UUID), tenant ID, client secret.
- Alibaba RAM user UID, AccessKey ID/Secret.
- OCI tenancy OCID, user OCID, fingerprint, private key PEM.
- DigitalOcean API token (32+ char hex).
- OpenStack project ID, password, application credential secret.
- Proxmox API token secret.

stderr 의 raw 메시지를 그대로 노출하면 위 정보가 섞일 수 있음. `ErrorResponse.ofSummarized`
가 자동 redact 하지 않으므로 caller 가 stderr 분류 후 신뢰할 수 있는 토큰만 message 로 전달.

## 4. 로그 redaction

`log.error("... {}", e.getMessage(), e)` 시 stderr 전체가 ELK / Loki / CloudWatch 등에 인덱싱
된다. logback-spring.xml 의 logstash encoder 가 자동 redact 하지 않으므로 다음 중 하나 필요:

- 로그 시점에 stderr 를 위 분류표 기반 short code (예: `CSP_AUTH_FAILED`, `CSP_API_DOWN`) 로
  변환 후 본문은 hash / truncate.
- 또는 redact pattern 을 logback 의 `PatternLayout` 또는 별도 `Converter` 로 적용 — 향후
  PR 에서 구현.

현재는 운영자가 log retention 정책 (30 일 default) 으로 노출 시간 제한.

## 5. Rate limiting

자격증명 실패 반복은 brute-force 시도 가능성 — 동일 user 가 짧은 시간에 다수 401/403 받으면
caller-side lockout 권장. 현재 미구현. 향후 별도 PR — IdempotencyFilter 와 함께 sliding
window 카운터.

## 6. Audit log

`audit_log` 에는 다음 형태로 기록:

- 성공: `user`, `clientIp`, `provider`, `credentialId` (해시 또는 마지막 4 자), `action`,
  `result=SUCCESS`.
- 실패: 위 + `result=PERMANENT_FAILURE` / `TRANSIENT_FAILURE`, `errorCode` (RAW STDERR 금지).
- credential row 없음: `result=NOT_FOUND`, raw input 은 hash.

`AuditLogger` 가 위 형식을 강제 — controller 가 자유롭게 reason 을 적어 노출하지 않도록 한다.

## 7. 변경 가이드

신규 CSP 통합 / 기존 stderr 매핑 추가 시:
1. 본 문서 § 2 표에 패턴 추가.
2. `CspCredentialServiceImpl` 또는 step service 의 catch 분기 변경 — 위 § 1 매트릭스에 맞춰
   `Transient` / `Permanent` 분류.
3. Bruno 시나리오 추가 — 잘못된 자격증명으로 provision 시 응답 body / log 가 § 3, § 4 정책
   준수 확인.
4. Security review — § 3 의 패턴 누설 검증.

본 문서는 single source of truth. 정책 변경 시 본 문서를 먼저 갱신 후 코드 반영.
