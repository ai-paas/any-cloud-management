-- CSP 자격증명의 sourceType (MANUAL/ENV) 추상화 제거. ENV 모드 (단일 backend ENV 자격증명 위임)
-- 가 multi-account 시나리오 불가 + audit 의미 없음 + cloud-native IAM 도입 시 strategy 추상화 필요로
-- 사용 가치 < 추상화 비용으로 판정. 모든 자격증명은 encrypt 경로로 통일.
--
-- csp_credential.source_type 및 vm_cluster.credential_source_type 동시 DROP.
-- 마이그레이션 안전 — 운영 환경 없음.

ALTER TABLE `csp_credential` DROP COLUMN `source_type`;

ALTER TABLE `vm_cluster` DROP COLUMN `credential_source_type`;
