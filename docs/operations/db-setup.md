# VM Cluster DB Setup

`VM Cluster` / `CSP Credential` 기능은 상태 저장용으로 `aipaas.vm_cluster`, `aipaas.csp_credential` 테이블을 사용합니다.

## 적용 방식

스키마는 **Flyway**가 부팅 시 자동으로 적용합니다. 마이그레이션 위치:

- `apps/anycloud/src/main/resources/db/migration/V1__create_vm_cluster.sql`
- `apps/anycloud/src/main/resources/db/migration/V2__create_csp_credential.sql`

부팅 시 동작:

1. Spring Boot 가 데이터소스에 연결합니다.
2. Flyway 가 `flyway_schema_history` 테이블을 확인/생성합니다.
3. 미적용 마이그레이션을 버전 순서대로 실행합니다.
4. JPA(`hibernate.ddl-auto=validate`)가 엔티티 매핑과 실제 스키마의 일치를 검증합니다.

## 신규 / 기존 환경 호환성

마이그레이션 파일은 모두 `CREATE TABLE IF NOT EXISTS`로 작성되어 멱등합니다.

- **신규 DB**: V1, V2 가 차례로 적용되어 테이블이 생성됩니다.
- **기존 DB (수동 SQL 이미 적용)**:
  - `spring.flyway.baseline-on-migrate=true`, `baseline-version=0` 으로 설정되어 있어 Flyway 가 처음 실행될 때 현재 스키마를 baseline 으로 인식합니다.
  - `IF NOT EXISTS` 덕분에 V1, V2 가 재실행되어도 무해합니다.
  - 결과적으로 `flyway_schema_history` 에 V1/V2 가 기록되고 이후 신규 버전부터 정상 추적됩니다.

## 운영 시 추가 변경 절차

스키마 변경이 필요할 때:

1. `db/migration/V{n}__short_description.sql` 파일을 추가합니다.
2. 같은 PR 에 JPA 엔티티 변경을 포함합니다.
3. 로컬에서 부팅하여 마이그레이션이 적용되고 JPA 검증이 통과하는지 확인합니다.
4. 운영 배포 시 별도 수동 작업 없이 백엔드 재기동만 하면 적용됩니다.

## 환경 변수

| 변수 | 기본값 | 설명 |
| --- | --- | --- |
| `SPRING_FLYWAY_ENABLED` | `true` | Flyway 비활성화가 필요할 때 (예: 외부에서 schema 를 관리하는 환경) `false` 로 설정합니다. |

## 확인 포인트

- `aipaas.flyway_schema_history` 에 V1, V2 row 가 보입니다 (`success=1`).
- `aipaas.vm_cluster`, `aipaas.csp_credential` 테이블 생성을 확인합니다.
- 엔티티와 컬럼 매핑이 어긋날 경우 JPA `validate` 단계에서 부팅이 실패하므로 즉시 인지가 가능합니다.
