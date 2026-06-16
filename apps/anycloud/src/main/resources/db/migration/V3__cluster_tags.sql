-- D-6 — ClusterEntity.tags JSON column.
-- 운영자 free-form label. RBAC starter (cluster-agent-rbac-spring-boot-starter) 의
-- tieredRoleRefs 매칭에 tags.anycloud.io/tier 사용. fleet view grouping / multi-tenant
-- ownership 등 추가 사용처는 운영자 선택.
--
-- MariaDB JSON column — 빈 객체 default.
ALTER TABLE cluster ADD COLUMN tags JSON NULL;
