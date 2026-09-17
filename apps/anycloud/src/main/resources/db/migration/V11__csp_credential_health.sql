-- 자격증명 확인 결과를 남긴다.
-- 화면 상태로만 두면 새로고침하면 사라지고, 사용자마다 각자 확인해야 한다.
ALTER TABLE csp_credential
    ADD COLUMN health_status VARCHAR(20) NULL COMMENT 'HEALTHY / UNHEALTHY. NULL 이면 확인한 적 없음',
    ADD COLUMN health_kind VARCHAR(40) NULL COMMENT 'CredentialFailureKind',
    ADD COLUMN health_detail VARCHAR(1000) NULL COMMENT 'CSP 원본 메시지',
    ADD COLUMN health_checked_at TIMESTAMP NULL;

-- 오래된 것만 다시 확인할 때 스캔한다.
CREATE INDEX idx_csp_credential_health_checked ON csp_credential (health_checked_at);
