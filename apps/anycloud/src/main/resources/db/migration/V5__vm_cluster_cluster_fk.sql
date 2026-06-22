-- vm_cluster ↔ cluster 사이의 implicit 매칭 (clusterName == cluster.id) 을 명시 FK 로 승격.
-- VM provision lifecycle 의 어느 시점부터 ClusterEntity 가 존재할지가 일정하지 않으므로
-- cluster_id 는 nullable. VERIFY 단계 또는 cluster-agent self-register 시점에 SET.
--
-- ON DELETE SET NULL — cluster 삭제 시 vm_cluster 의 audit row 는 보존, FK 만 끊는다.
-- (cluster row 삭제 ≠ Pulumi stack destroy. 운영자 수동 cluster 제거 시에도 VM history 유지.)

ALTER TABLE `vm_cluster`
  ADD COLUMN `cluster_id` varchar(45) DEFAULT NULL AFTER `cluster_name`;

-- 기존 데이터 backfill: cluster_name 으로 cluster.id 매칭. 이름 일치 row 만 link.
UPDATE `vm_cluster` v
  JOIN `cluster` c ON c.id = v.cluster_name
  SET v.cluster_id = c.id;

ALTER TABLE `vm_cluster`
  ADD CONSTRAINT `fk_vm_cluster_cluster`
    FOREIGN KEY (`cluster_id`) REFERENCES `cluster` (`id`)
    ON DELETE SET NULL ON UPDATE CASCADE,
  ADD KEY `idx_vm_cluster_cluster_id` (`cluster_id`);
