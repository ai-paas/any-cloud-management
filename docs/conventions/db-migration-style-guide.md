# Flyway Migration Style Guide

anycloud 의 `apps/anycloud/src/main/resources/db/migration/` 작성 규칙입니다.

## 1. 파일 명명

```
V<sequence>__<lowercase_snake_case_description>.sql
```

- `<sequence>`: integer (V1, V2, ...) 또는 dotted (V0_5) 입니다. 의도적 gap (다른 sprint 에서 squash) 의 경우 §2 를 참조합니다.
- `<description>`: 50 자 이내로, action verb 로 시작하기를 권장합니다 (`create_`, `alter_`, `drop_`).

### 예시 ✅
- `V30__create_cluster_addon.sql`
- `V32__operation_type_state_to_varchar.sql`
- `V33__create_backup_history.sql`

### 안티 ❌
- `V100_something.sql` (single underscore — Flyway 인식 안 됨)
- `V31__ClusterAddonIdSize.sql` (CamelCase)
- `V33__migration.sql` (의미 없는 description)

## 2. Sequence number gap 정책

**의도적 gap 은 허용합니다**. 사유 (squash, deprecation, sprint cancellation) 가 commit 기록에 남아야 합니다.

현재 sequence gap 은 다음과 같습니다.

| Number | 사유 |
| --- | --- |
| V14 | `cluster_agent_mtls_cert` 삭제 (mTLS 제거, Bearer 단일 인증 전환) |
| V24 | `cluster_agent_cert_serial_unique` 삭제 (V14 동반 삭제) |

→ Gap 발견 시 git log 명령은 다음과 같습니다.
```bash
git log --diff-filter=D --pretty=format:'%h %ai %s' -- "**/V<N>__*"
```

## 3. CREATE TABLE 작성 규칙

### 3.1 ENGINE / CHARSET / COLLATE — **MariaDB server default 따르기**

```sql
-- ✅ 권장 — server default 따라가게 명시 X
CREATE TABLE foo (
  ...
) ENGINE=InnoDB;

-- ❌ 비권장 — 명시 시 다른 table 과 collation 불일치 위험 (FK errno 150)
CREATE TABLE foo (
  ...
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
```

**사유**: FK referenced column (예: `cluster.id`) 의 collation 과 referencing column 의 collation 이
정확히 동일해야 합니다. 일부 migration 에만 명시할 경우 errno 150 이 발생합니다.

> **예외**: TEXT/BLOB 안의 emoji / 다국어 지원 명시가 필요할 때는 column-level `CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci` 를 적용합니다. table-level 보다 column-level 이 명확합니다.

### 3.2 PRIMARY KEY + AUTO_INCREMENT

```sql
-- ✅ String PK (UUID / nanoid) — backend 가 생성
`id` VARCHAR(45) NOT NULL,
PRIMARY KEY (`id`)

-- ⚠️ AUTO_INCREMENT — replication / sharding 시 충돌. 단일 instance dev 전용.
`id` BIGINT NOT NULL AUTO_INCREMENT,
PRIMARY KEY (`id`)
```

### 3.3 Audit columns

```sql
`created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
`updated_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
```

DATETIME(3) 을 권장합니다 (millisecond precision). DATETIME 만 사용하면 second precision 으로 인해 audit/ordering edge case 가 발생합니다.

### 3.4 Comments (필수)

```sql
-- V<N>: <one-line description>.
--
-- 목적: <비즈니스/기술 사유>.
--
-- 설계 결정:
--   1) <key decision> — <근거>
--   2) ...
--
-- State machine (해당 시):
--   <STATE_1> → ...

CREATE TABLE ... ;
```

V30 / V33 이 모범 사례입니다. 새 migration 작성 시 본 template 을 따릅니다.

## 4. ALTER TABLE 작성 규칙

### 4.1 ALTER vs DROP+CREATE

- column 추가/변경 → `ALTER TABLE` 을 사용합니다.
- 전체 schema 재설계 → 새 table 생성 + INSERT INTO ... SELECT + DROP old + RENAME 순서로 진행합니다.

### 4.2 Backfill

```sql
-- ✅ Single migration 안에서 schema 변경 + data backfill 동시 처리.
ALTER TABLE cluster ADD COLUMN region VARCHAR(50) NULL;
UPDATE cluster SET region = 'unknown' WHERE region IS NULL;
ALTER TABLE cluster MODIFY COLUMN region VARCHAR(50) NOT NULL;
```

순서가 중요합니다: NULL 추가 → backfill → NOT NULL 순서로 진행합니다.

## 5. FOREIGN KEY 작성 규칙

### 5.1 Charset matching (critical)

referenced + referencing column 의 **collation 이 정확히 일치**해야 합니다.

```sql
-- ❌ 사고 예 (V33 FK errno 150)
CREATE TABLE backup_history (
  cluster_id VARCHAR(45) NOT NULL,    -- collation: server default (e.g. utf8mb4_general_ci)
  FOREIGN KEY (cluster_id) REFERENCES cluster(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
-- ↑ table-level utf8mb4_unicode_ci 가 cluster.id 의 default 와 불일치
```

→ **§3.1 의 server default 규칙을 준수합니다**.

### 5.2 ON DELETE 정책 명시

```sql
FOREIGN KEY (cluster_id) REFERENCES cluster(id)
    ON DELETE CASCADE       -- child rows 자동 삭제 (audit history 처럼 cluster 와 lifecycle 일치)
ON DELETE RESTRICT      -- referenced 가 있으면 parent 삭제 거부 (default)
ON DELETE SET NULL      -- child column 을 NULL 로 (옵션)
```

명시하지 않으면 RESTRICT 가 적용되므로, 운영자에게 의도를 명확히 하기 위해 항상 명시합니다.

## 6. INDEX 작성

### 6.1 명명

```sql
KEY `idx_<table>_<col1>_<col2>` (col1, col2)
KEY `idx_<table>_<col>_<sort>`  (col DESC)     -- sort 의도 명시
UNIQUE KEY `uq_<table>_<col>`   (col)
```

### 6.2 Composite index 순서

```sql
-- "최근 backup 목록" 쿼리: WHERE cluster_id = ? ORDER BY started_at DESC
-- → cluster_id 가 prefix, started_at 이 secondary. 본 순서 INDEX.
KEY `idx_backup_history_cluster_started` (`cluster_id`, `started_at` DESC)
```

## 7. ENUM 사용 정책

### 7.1 application-managed enum (권장)

```sql
`status` VARCHAR(32) NOT NULL COMMENT 'STARTED / SUCCEEDED / FAILED — enum 은 application 측 관리'
```

→ 새 enum value 추가 시 DB migration 이 필요하지 않습니다. 단점은 DB 측 type safety 가 없다는 점입니다 (validation 은 app 책임).

### 7.2 DB ENUM (제한적)

```sql
-- ⚠️ DB ENUM 도입 시 신중. JPA 의 @Enumerated(STRING) 와 결합되어 enum value 추가 마다 ALTER 필요.
```

→ **VARCHAR 를 권장합니다**. JPA `@Convert` 또는 application 측 검증으로 type safety 를 확보합니다.

## 8. Migration 적용 검증

### 8.1 Dev 환경

```bash
docker compose -f docker-compose.dev.yml down -v   # 깨끗한 state
make dev-up                                         # Flyway 가 모든 migration apply
```

### 8.2 운영 환경

- `flyway info` 로 pending migration 을 확인합니다.
- `flyway migrate --dry-run` 으로 무중단 환경에서 미리 확인합니다 (운영자 작업).
- 절대 `flyway repair` 를 자동화하지 않습니다 — 항상 수동으로 검토합니다.

## 9. Anti-pattern 회피

| Anti-pattern | 회피 방법 |
| --- | --- |
| Migration 안에서 `SELECT` 의존 응용 로직 | 별 service 로 분리. migration 은 schema 만 |
| 큰 row 일괄 update (100k+) | batched UPDATE + LIMIT. lock 시간 단축 |
| Production 에서 migration repair | dev 에서 충분히 검증 후 production apply |
| 명시 collation 일부 table 만 적용 | §3.1 규칙 — 전체 server default 통일 |
| 단일 migration 의 multiple unrelated change | 1 migration = 1 concern 원칙 |

## 10. 관련 doc

- migration history: `git log apps/anycloud/src/main/resources/db/migration/`
- `apps/anycloud/src/main/resources/db/migration/V0_5__*.sql` 부터 — migration 시퀀스 시작점입니다.
