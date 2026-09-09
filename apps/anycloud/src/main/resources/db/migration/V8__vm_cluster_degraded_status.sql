-- DEGRADED: Kubernetes API 는 정상이나 REQUIRED 컴포넌트가 미충족인 상태.
-- 상태 컬럼이 varchar 가 아니라 MySQL enum 이라 값 추가에 DDL 이 필요하다. Java 만 고치면
-- 컴파일과 단위 테스트는 통과하고 실제 저장 시점에 깨진다.
ALTER TABLE `vm_cluster`
  MODIFY COLUMN `provisioning_status`
    enum('REQUESTED','PROVISIONING','BOOTSTRAPPING','VERIFYING','READY','DEGRADED',
         'SCALING','UPGRADING','FAILED','BLOCKED','DELETING','DELETED') NOT NULL;

ALTER TABLE `vm_cluster_state_history`
  MODIFY COLUMN `from_state`
    enum('REQUESTED','PROVISIONING','BOOTSTRAPPING','VERIFYING','READY','DEGRADED',
         'SCALING','UPGRADING','FAILED','BLOCKED','DELETING','DELETED') DEFAULT NULL;

ALTER TABLE `vm_cluster_state_history`
  MODIFY COLUMN `to_state`
    enum('REQUESTED','PROVISIONING','BOOTSTRAPPING','VERIFYING','READY','DEGRADED',
         'SCALING','UPGRADING','FAILED','BLOCKED','DELETING','DELETED') NOT NULL;
