-- III-51: bootstrap 진행 가시성 — vm_cluster 에 sub-step + 에러 분류 컬럼 추가.
-- V1 baseline 은 불변 (이미 적용된 환경의 checksum 보존) — 신규 변경은 별도 버전으로.
ALTER TABLE `vm_cluster`
  ADD COLUMN `last_error_code` varchar(50) DEFAULT NULL AFTER `last_error`,
  ADD COLUMN `current_sub_step` varchar(50) DEFAULT NULL AFTER `bootstrap_log`,
  ADD COLUMN `sub_step_started_at` timestamp NULL DEFAULT NULL AFTER `current_sub_step`;
