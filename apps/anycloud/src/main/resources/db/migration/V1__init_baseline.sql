-- V1 — anycloud schema baseline (2026-06-10)
--
-- 이 file 은 dev 단계에서 V0_5 ~ V34 의 32 migration 을 통합한 baseline 입니다.
-- 운영 배포 전이라 history 손실 영향 없음 (git log 에 원본 의도 보존).
--
-- 통합 대상 (운영 table 17):
--   agent_signing_key, audit_log, backup_history, bootstrap_jti_used, cluster,
--   cluster_addon, cluster_agent, csp_credential, fleet_upgrade_run, helm_repo,
--   idempotency_record, oidc_group_binding, operation, shedlock, vm_cluster,
--   vm_cluster_state_history, workflow_message_log
--
-- FK 의존성 (생성 순서 무관 — foreign_key_checks=0 로 감쌈):
--   backup_history.cluster_id → cluster.id (ON DELETE CASCADE)
--   cluster_addon.cluster_id  → cluster.id (ON DELETE CASCADE)

SET FOREIGN_KEY_CHECKS = 0;

CREATE TABLE `agent_signing_key` (
  `id` bigint(20) NOT NULL AUTO_INCREMENT,
  `key_id` varchar(36) NOT NULL,
  `key_bytes` varbinary(128) NOT NULL,
  `algorithm` varchar(20) NOT NULL DEFAULT 'HS256',
  `active` tinyint(1) NOT NULL DEFAULT 1,
  `created_at` timestamp NOT NULL DEFAULT current_timestamp(),
  `deactivated_at` timestamp NULL DEFAULT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_agent_signing_key_kid` (`key_id`),
  KEY `idx_agent_signing_key_active` (`active`,`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;
CREATE TABLE `audit_log` (
  `id` varchar(36) NOT NULL,
  `request_id` varchar(64) DEFAULT NULL,
  `principal` varchar(128) DEFAULT NULL,
  `client_ip` varchar(64) DEFAULT NULL,
  `http_method` varchar(8) DEFAULT NULL,
  `path` varchar(512) DEFAULT NULL,
  `action` varchar(64) NOT NULL,
  `resource_type` varchar(64) DEFAULT NULL,
  `resource_id` varchar(128) DEFAULT NULL,
  `status_code` int(11) DEFAULT NULL,
  `duration_ms` bigint(20) DEFAULT NULL,
  `error_message` mediumtext DEFAULT NULL,
  `request_summary` mediumtext DEFAULT NULL,
  `created_at` timestamp NOT NULL DEFAULT current_timestamp(),
  PRIMARY KEY (`id`),
  KEY `idx_audit_log_resource` (`resource_type`,`resource_id`,`created_at`),
  KEY `idx_audit_log_principal_created_at` (`principal`,`created_at`),
  KEY `idx_audit_log_action_created_at` (`action`,`created_at`),
  KEY `idx_audit_log_request_id` (`request_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;
CREATE TABLE `backup_history` (
  `id` varchar(64) NOT NULL,
  `cluster_id` varchar(45) NOT NULL,
  `backup_type` varchar(32) NOT NULL COMMENT 'enum-free for future extensibility',
  `velero_backup_name` varchar(253) DEFAULT NULL COMMENT 'Velero Backup CR metadata.name',
  `status` varchar(16) NOT NULL COMMENT 'lifecycle state',
  `size_bytes` bigint(20) DEFAULT NULL COMMENT 'backup artifact size (bytes); NULL until completed',
  `storage_uri` varchar(512) DEFAULT NULL COMMENT 'external storage location; agent 측 결정',
  `requested_by` varchar(255) DEFAULT NULL COMMENT 'OIDC user; NULL = system/scheduled action',
  `error` text DEFAULT NULL COMMENT 'failure message (status=FAILED)',
  `started_at` datetime(3) NOT NULL DEFAULT current_timestamp(3),
  `completed_at` datetime(3) DEFAULT NULL,
  PRIMARY KEY (`id`),
  KEY `idx_backup_history_cluster_started` (`cluster_id`,`started_at` DESC),
  KEY `idx_backup_history_type_status` (`backup_type`,`status`),
  CONSTRAINT `fk_backup_history_cluster` FOREIGN KEY (`cluster_id`) REFERENCES `cluster` (`id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='BACKUP_ETCD / BACKUP_PKI / Velero backup 의 영구 기록 (P-4, 2026-06-08).';
CREATE TABLE `bootstrap_jti_used` (
  `jti` varchar(64) NOT NULL,
  `used_at` timestamp NOT NULL DEFAULT current_timestamp(),
  `expires_at` timestamp NOT NULL,
  PRIMARY KEY (`jti`),
  KEY `idx_bootstrap_jti_used_expires` (`expires_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;
CREATE TABLE `cluster` (
  `id` varchar(45) NOT NULL,
  `description` varchar(255) DEFAULT NULL,
  `status` enum('UNKNOWN','AGENT_PENDING','ACTIVE','INACTIVE') DEFAULT NULL,
  `version` varchar(45) DEFAULT NULL,
  `cluster_type` varchar(100) NOT NULL,
  `cluster_provider` varchar(100) NOT NULL,
  `provisioning_type` varchar(50) DEFAULT NULL,
  `provisioning_status` varchar(50) DEFAULT NULL,
  `has_gpu_nodes` tinyint(1) NOT NULL DEFAULT 0,
  `stack_name` varchar(100) DEFAULT NULL,
  `created_at` timestamp NOT NULL DEFAULT current_timestamp(),
  `updated_at` timestamp NOT NULL DEFAULT current_timestamp() ON UPDATE current_timestamp(),
  PRIMARY KEY (`id`),
  KEY `idx_cluster_provider_type` (`cluster_provider`,`cluster_type`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;
CREATE TABLE `cluster_addon` (
  `id` varchar(64) NOT NULL COMMENT 'addon-<UUID>',
  `cluster_id` varchar(45) NOT NULL,
  `addon_type` enum('MONITORING','VELERO','GPU_EXPORTER','CERT_MANAGER','INGRESS_NGINX','GENERIC') NOT NULL,
  `catalog_id` varchar(64) DEFAULT NULL COMMENT 'Option B catalog id ref',
  `release_name` varchar(128) NOT NULL,
  `namespace` varchar(128) NOT NULL,
  `chart_repo` varchar(128) NOT NULL,
  `chart_name` varchar(128) NOT NULL,
  `chart_version` varchar(32) NOT NULL,
  `repo_url` varchar(512) DEFAULT NULL COMMENT 'HelmRepoEntity.url snapshot or explicit override',
  `values_yaml` mediumtext DEFAULT NULL COMMENT 'optional values override',
  `state` enum('PENDING','ENQUEUED','INSTALLING','SUCCEEDED','FAILED','DELETING','DELETED') NOT NULL,
  `last_operation_id` varchar(36) DEFAULT NULL,
  `last_error` text DEFAULT NULL,
  `attempts` int(11) NOT NULL DEFAULT 0,
  `enabled` tinyint(1) NOT NULL DEFAULT 1,
  `created_at` timestamp NOT NULL DEFAULT current_timestamp(),
  `updated_at` timestamp NOT NULL DEFAULT current_timestamp() ON UPDATE current_timestamp(),
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_cluster_addon_release` (`cluster_id`,`namespace`,`release_name`),
  KEY `idx_cluster_addon_cluster` (`cluster_id`),
  KEY `idx_cluster_addon_state` (`state`),
  KEY `idx_cluster_addon_type` (`addon_type`),
  CONSTRAINT `fk_cluster_addon_cluster` FOREIGN KEY (`cluster_id`) REFERENCES `cluster` (`id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;
CREATE TABLE `cluster_agent` (
  `agent_id` varchar(36) NOT NULL,
  `cluster_name` varchar(128) NOT NULL,
  `agent_instance_id` varchar(64) NOT NULL,
  `k8s_cluster_uid` varchar(64) DEFAULT NULL,
  `identity_token_hash` varchar(128) NOT NULL,
  `status` enum('REGISTERING','REGISTERED','ACTIVE','DEGRADED','FAILED','REVOKED') NOT NULL,
  `agent_version` varchar(32) DEFAULT NULL,
  `distribution` varchar(32) DEFAULT NULL,
  `k8s_version` varchar(32) DEFAULT NULL,
  `endpoint` varchar(256) DEFAULT NULL,
  `public_ip` varchar(64) DEFAULT NULL,
  `private_ip` varchar(64) DEFAULT NULL,
  `pod_cidr` varchar(64) DEFAULT NULL,
  `service_cidr` varchar(64) DEFAULT NULL,
  `registered_at` timestamp NULL DEFAULT NULL,
  `last_seen_at` timestamp NULL DEFAULT NULL,
  `last_k8s_api_ok_at` timestamp NULL DEFAULT NULL,
  `expires_at` timestamp NULL DEFAULT NULL,
  `revoked_at` timestamp NULL DEFAULT NULL,
  `last_error` text DEFAULT NULL,
  `created_at` timestamp NOT NULL DEFAULT current_timestamp(),
  `updated_at` timestamp NOT NULL DEFAULT current_timestamp() ON UPDATE current_timestamp(),
  `upgrade_wave` enum('CANARY','STAGING','GENERAL','PAUSED') NOT NULL,
  `upgrade_status` enum('IDLE','PENDING','IN_PROGRESS','SUCCEEDED','FAILED') NOT NULL,
  `upgrade_target_image` varchar(256) DEFAULT NULL COMMENT 'In-flight upgrade 의 target docker image (registry/repo:tag)',
  `upgrade_source_version` varchar(32) DEFAULT NULL COMMENT 'Upgrade 시작 시점의 agent_version 스냅샷 — 회귀 / abort 시 참고',
  `upgrade_started_at` datetime DEFAULT NULL COMMENT 'Upgrade trigger 시각. 60min 안에 SUCCEEDED 안 되면 FAILED 자동 전환',
  `upgrade_completed_at` datetime DEFAULT NULL COMMENT 'SUCCEEDED / FAILED 시점',
  `upgrade_error` text DEFAULT NULL COMMENT 'FAILED 일 때 원인',
  PRIMARY KEY (`agent_id`),
  UNIQUE KEY `uk_cluster_agent_token_hash` (`identity_token_hash`),
  KEY `idx_cluster_agent_cluster_name` (`cluster_name`),
  KEY `idx_cluster_agent_status` (`status`),
  KEY `idx_cluster_agent_last_seen` (`last_seen_at`),
  KEY `idx_cluster_agent_upgrade_wave` (`upgrade_wave`),
  KEY `idx_cluster_agent_upgrade_status` (`upgrade_status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;
CREATE TABLE `csp_credential` (
  `id` varchar(36) NOT NULL,
  `provider` varchar(100) NOT NULL,
  `name` varchar(100) NOT NULL,
  `description` varchar(255) DEFAULT NULL,
  `source_type` enum('MANUAL','ENV') NOT NULL,
  `encrypted_payload` longtext DEFAULT NULL,
  `credential_keys` text DEFAULT NULL,
  `active` bit(1) NOT NULL DEFAULT b'1',
  `created_at` timestamp NOT NULL DEFAULT current_timestamp(),
  `updated_at` timestamp NOT NULL DEFAULT current_timestamp() ON UPDATE current_timestamp(),
  PRIMARY KEY (`id`),
  KEY `idx_csp_credential_provider_created_at` (`provider`,`created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;
CREATE TABLE `fleet_upgrade_run` (
  `run_id` varchar(36) NOT NULL COMMENT 'UUID v4',
  `target_image` varchar(256) NOT NULL COMMENT 'docker image (registry/repo:tag)',
  `waves_csv` varchar(128) NOT NULL COMMENT '처리할 wave들 CSV (CANARY,STAGING,GENERAL)',
  `current_wave` varchar(16) DEFAULT NULL COMMENT '진행 중 wave (RUNNING 일 때만)',
  `status` enum('PLANNED','RUNNING','PAUSED','COMPLETED','ABORTED') NOT NULL,
  `concurrency` int(11) NOT NULL DEFAULT 5 COMMENT 'wave 안에서 동시 진행 cluster 수',
  `failure_threshold` int(11) NOT NULL DEFAULT 20 COMMENT '단일 wave failure rate(%) — 초과 시 자동 abort',
  `total_clusters` int(11) NOT NULL DEFAULT 0,
  `succeeded_count` int(11) NOT NULL DEFAULT 0,
  `failed_count` int(11) NOT NULL DEFAULT 0,
  `skipped_count` int(11) NOT NULL DEFAULT 0 COMMENT 'PAUSED wave / 이미 target version cluster',
  `created_by` varchar(64) DEFAULT NULL COMMENT 'REST caller (audit interceptor 가 추출)',
  `created_at` datetime NOT NULL DEFAULT current_timestamp(),
  `started_at` datetime DEFAULT NULL,
  `completed_at` datetime DEFAULT NULL,
  `last_error` text DEFAULT NULL COMMENT 'ABORTED 시 원인',
  PRIMARY KEY (`run_id`),
  KEY `idx_fleet_upgrade_run_status` (`status`),
  KEY `idx_fleet_upgrade_run_created_at` (`created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;
CREATE TABLE `helm_repo` (
  `id` varchar(36) NOT NULL,
  `name` varchar(100) NOT NULL,
  `url` varchar(100) NOT NULL,
  `username` varchar(100) DEFAULT NULL,
  `password` varchar(100) DEFAULT NULL,
  `ca_file` tinytext DEFAULT NULL,
  `insecure_skip_tls_verify` tinyint(1) NOT NULL DEFAULT 0,
  `created_at` timestamp NOT NULL DEFAULT current_timestamp(),
  `updated_at` timestamp NOT NULL DEFAULT current_timestamp() ON UPDATE current_timestamp(),
  `source` enum('INTERNAL','EXTERNAL') NOT NULL DEFAULT 'EXTERNAL',
  `tags` varchar(255) DEFAULT NULL COMMENT 'Free-form comma-separated tags (UI filter)',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_helm_repo_name` (`name`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;
CREATE TABLE `idempotency_record` (
  `idempotency_key` varchar(128) NOT NULL,
  `request_fingerprint` varchar(64) NOT NULL,
  `status_code` int(11) NOT NULL,
  `response_body` mediumtext DEFAULT NULL,
  `created_at` timestamp NOT NULL DEFAULT current_timestamp(),
  `expires_at` timestamp NOT NULL,
  PRIMARY KEY (`idempotency_key`),
  KEY `idx_idempotency_expires_at` (`expires_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;
CREATE TABLE `oidc_group_binding` (
  `name` varchar(253) NOT NULL,
  `description` varchar(512) DEFAULT NULL,
  `spec` longtext CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL CHECK (json_valid(`spec`)),
  `active` tinyint(1) NOT NULL DEFAULT 1,
  `created_at` datetime(6) NOT NULL,
  `updated_at` datetime(6) NOT NULL,
  PRIMARY KEY (`name`),
  KEY `idx_oidc_binding_active` (`active`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;
CREATE TABLE `operation` (
  `id` varchar(36) NOT NULL,
  `type` enum('CREATE_CLUSTER','SCALE_CLUSTER','UPGRADE_CLUSTER','DELETE_CLUSTER','RETRY_WORKFLOW','RETRY_REGISTRATION','REFRESH_STATUS','CONNECTIVITY_CHECK','INSTALL_HELM_RELEASE','UPGRADE_HELM_RELEASE','ROLLBACK_HELM_RELEASE','UNINSTALL_HELM_RELEASE','INSTALL_ADDON','UNINSTALL_ADDON','REPLAY_DEAD_LETTER_MESSAGE') NOT NULL,
  `resource_type` varchar(48) NOT NULL,
  `resource_id` varchar(128) NOT NULL,
  `state` enum('PENDING','RUNNING','SUCCEEDED','FAILED','CANCELLED') NOT NULL,
  `current_step` varchar(48) DEFAULT NULL,
  `step_index` int(11) DEFAULT NULL,
  `total_steps` int(11) DEFAULT NULL,
  `percent` int(11) DEFAULT NULL,
  `request_payload` mediumtext DEFAULT NULL,
  `result_payload` mediumtext DEFAULT NULL,
  `error_message` mediumtext DEFAULT NULL,
  `request_id` varchar(64) DEFAULT NULL,
  `principal` varchar(128) DEFAULT NULL,
  `started_at` timestamp NULL DEFAULT NULL,
  `ended_at` timestamp NULL DEFAULT NULL,
  `created_at` timestamp NOT NULL DEFAULT current_timestamp(),
  `updated_at` timestamp NOT NULL DEFAULT current_timestamp() ON UPDATE current_timestamp(),
  PRIMARY KEY (`id`),
  KEY `idx_operation_resource_created` (`resource_type`,`resource_id`,`created_at`),
  KEY `idx_operation_state_created` (`state`,`created_at`),
  KEY `idx_operation_request_id` (`request_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;
CREATE TABLE `shedlock` (
  `name` varchar(64) NOT NULL,
  `lock_until` timestamp(3) NOT NULL,
  `locked_at` timestamp(3) NOT NULL,
  `locked_by` varchar(255) NOT NULL,
  PRIMARY KEY (`name`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;
CREATE TABLE `vm_cluster` (
  `id` varchar(36) NOT NULL,
  `cluster_name` varchar(45) NOT NULL,
  `description` varchar(255) DEFAULT NULL,
  `cluster_provider` varchar(100) NOT NULL,
  `provisioning_status` enum('REQUESTED','PROVISIONING','BOOTSTRAPPING','VERIFYING','READY','SCALING','UPGRADING','FAILED','BLOCKED','DELETING','DELETED') NOT NULL,
  `stack_name` varchar(100) NOT NULL,
  `region` varchar(100) DEFAULT NULL,
  `environment` varchar(50) DEFAULT NULL,
  `active_request_key` varchar(45) DEFAULT NULL,
  `credential_id` varchar(36) DEFAULT NULL,
  `credential_name` varchar(100) DEFAULT NULL,
  `credential_source_type` enum('MANUAL','ENV') DEFAULT NULL,
  `request_config` longtext DEFAULT NULL,
  `raw_outputs` longtext DEFAULT NULL,
  `last_error` mediumtext DEFAULT NULL,
  `bootstrap_log` mediumtext DEFAULT NULL,
  `current_workflow_step` enum('PROVISION','BOOTSTRAP','VERIFY','DESTROY') DEFAULT NULL,
  `last_successful_step` enum('PROVISION','BOOTSTRAP','VERIFY','DESTROY') DEFAULT NULL,
  `last_failed_step` enum('PROVISION','BOOTSTRAP','VERIFY','DESTROY') DEFAULT NULL,
  `workflow_retry_count` int(11) NOT NULL DEFAULT 0,
  `last_processed_workflow_message_id` varchar(36) DEFAULT NULL,
  `requested_at` timestamp NULL DEFAULT NULL,
  `provisioning_started_at` timestamp NULL DEFAULT NULL,
  `bootstrapping_started_at` timestamp NULL DEFAULT NULL,
  `verifying_started_at` timestamp NULL DEFAULT NULL,
  `ready_at` timestamp NULL DEFAULT NULL,
  `failed_at` timestamp NULL DEFAULT NULL,
  `deleting_started_at` timestamp NULL DEFAULT NULL,
  `deleted_at` timestamp NULL DEFAULT NULL,
  `cluster_registered` bit(1) NOT NULL DEFAULT b'0',
  `created_at` timestamp NOT NULL DEFAULT current_timestamp(),
  `updated_at` timestamp NOT NULL DEFAULT current_timestamp() ON UPDATE current_timestamp(),
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_vm_cluster_active_request_key` (`active_request_key`),
  KEY `idx_vm_cluster_cluster_name_created_at` (`cluster_name`,`created_at`),
  KEY `idx_vm_cluster_credential_id` (`credential_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;
CREATE TABLE `vm_cluster_state_history` (
  `id` varchar(36) NOT NULL,
  `cluster_name` varchar(128) NOT NULL,
  `vm_cluster_id` varchar(36) DEFAULT NULL,
  `from_state` enum('REQUESTED','PROVISIONING','BOOTSTRAPPING','VERIFYING','READY','SCALING','UPGRADING','FAILED','BLOCKED','DELETING','DELETED') DEFAULT NULL,
  `to_state` enum('REQUESTED','PROVISIONING','BOOTSTRAPPING','VERIFYING','READY','SCALING','UPGRADING','FAILED','BLOCKED','DELETING','DELETED') NOT NULL,
  `reason` varchar(128) DEFAULT NULL,
  `principal` varchar(128) DEFAULT NULL,
  `request_id` varchar(64) DEFAULT NULL,
  `valid` tinyint(1) NOT NULL DEFAULT 1,
  `created_at` timestamp NOT NULL DEFAULT current_timestamp(),
  PRIMARY KEY (`id`),
  KEY `idx_vmcluster_history_name` (`cluster_name`),
  KEY `idx_vmcluster_history_created` (`created_at`),
  KEY `idx_vmcluster_history_invalid` (`valid`,`created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
CREATE TABLE `workflow_message_log` (
  `id` varchar(36) NOT NULL,
  `message_id` varchar(36) NOT NULL,
  `vm_cluster_id` varchar(36) DEFAULT NULL,
  `cluster_name` varchar(45) DEFAULT NULL,
  `step` enum('PROVISION','BOOTSTRAP','VERIFY','DESTROY') NOT NULL,
  `result` enum('PROCESSED','SKIPPED_DUPLICATE','SKIPPED_STALE','SKIPPED_NOT_FOUND','FAILED') NOT NULL,
  `started_at` timestamp NOT NULL DEFAULT current_timestamp(),
  `completed_at` timestamp NULL DEFAULT NULL,
  `duration_ms` bigint(20) DEFAULT NULL,
  `error_message` mediumtext DEFAULT NULL,
  `created_at` timestamp NOT NULL DEFAULT current_timestamp(),
  PRIMARY KEY (`id`),
  KEY `idx_workflow_log_message_id` (`message_id`),
  KEY `idx_workflow_log_vm_cluster_id_created_at` (`vm_cluster_id`,`created_at`),
  KEY `idx_workflow_log_cluster_name_created_at` (`cluster_name`,`created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;

SET FOREIGN_KEY_CHECKS = 1;
