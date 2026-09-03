-- VM 위 계층(GPU 드라이버, GPU operator, ingress, agent)의 desired 대비 observed 상태.
-- vm_cluster_id 는 vm_cluster.id 와 같은 varchar(36) 이어야 FK 를 걸 수 있다.
CREATE TABLE `vm_cluster_component` (
  `id`              varchar(64) NOT NULL,
  `vm_cluster_id`   varchar(36) NOT NULL,
  `component_type`  varchar(32) NOT NULL,
  `requirement`     varchar(16) NOT NULL,
  `auto_repair`     tinyint(1)  NOT NULL DEFAULT 1,
  `health`          varchar(16) NOT NULL,
  `attempts`        int(11)     NOT NULL DEFAULT 0,
  `next_attempt_at` datetime(6) DEFAULT NULL,
  `last_probed_at`  datetime(6) DEFAULT NULL,
  `last_applied_at` datetime(6) DEFAULT NULL,
  `last_error`      text        DEFAULT NULL,
  `created_at`      datetime(6) NOT NULL,
  `updated_at`      datetime(6) NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_vm_cluster_component` (`vm_cluster_id`, `component_type`),
  KEY `idx_vm_cluster_component_next_attempt` (`next_attempt_at`),
  CONSTRAINT `fk_vm_cluster_component_cluster`
    FOREIGN KEY (`vm_cluster_id`) REFERENCES `vm_cluster` (`id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
