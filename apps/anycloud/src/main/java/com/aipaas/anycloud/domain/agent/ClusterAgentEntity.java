package com.aipaas.anycloud.domain.agent;

import com.aipaas.anycloud.domain.agent.model.ClusterAgentStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

/**
 * In-cluster Cluster Agent 의 등록/런타임 상태.
 *
 * <p>bootstrap RPC 가 끝나면 row 가 생긴다. 하나의 cluster 가 HA 로 여러 agent
 * 인스턴스를 가질 수 있으므로 (instance_id 별 row), PK 는 agent_id (UUID).
 *
 * <p>{@code identity_token_hash} 는 hex string — 원본 token 은 발급 직후 agent 에게
 * 만 전달되고 backend 는 hash 만 보관 (DB 유출 시 token 직접 재사용 차단).
 */
@Entity
@Table(name = "cluster_agent")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ClusterAgentEntity {

    @Id
    @Column(name = "agent_id", length = 36, nullable = false)
    private String agentId;

    @Column(name = "cluster_name", length = 128, nullable = false)
    private String clusterName;

    @Column(name = "agent_instance_id", length = 64, nullable = false)
    private String agentInstanceId;

    @Column(name = "k8s_cluster_uid", length = 64)
    private String k8sClusterUid;

    /** (token) hex. Constant-time 비교용. 원본 token 은 발급 직후 agent 에게만. */
    @Column(name = "identity_token_hash", length = 128, nullable = false)
    private String identityTokenHash;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", length = 24, nullable = false)
    private ClusterAgentStatus status;

    @Column(name = "agent_version", length = 32)
    private String agentVersion;

    @Column(name = "distribution", length = 32)
    private String distribution;

    @Column(name = "k8s_version", length = 32)
    private String k8sVersion;

    @Column(name = "endpoint", length = 256)
    private String endpoint;

    @Column(name = "public_ip", length = 64)
    private String publicIp;

    @Column(name = "private_ip", length = 64)
    private String privateIp;

    @Column(name = "pod_cidr", length = 64)
    private String podCidr;

    @Column(name = "service_cidr", length = 64)
    private String serviceCidr;

    @Column(name = "registered_at")
    private LocalDateTime registeredAt;

    @Column(name = "last_seen_at")
    private LocalDateTime lastSeenAt;

    /**
     * Agent → K8s API server 의 마지막 성공 호출 시각. Heartbeat 의 AgentHealth.last_k8s_api_ok 에서
     * 갱신. last_seen_at 은 stream 레벨, 본 컬럼은 K8s 통신 가능 여부 — 둘 다 fresh 해야 cluster
     * 실제 사용 가능.
     */
    @Column(name = "last_k8s_api_ok_at")
    private LocalDateTime lastK8sApiOkAt;

    /** agent_identity_token 만료 시각. 자동 갱신 cycle 의 기준. */
    @Column(name = "expires_at")
    private LocalDateTime expiresAt;

    /** null = active. set = revoked. Bearer 인증 시 본 컬럼 체크 필수. */
    @Column(name = "revoked_at")
    private LocalDateTime revokedAt;

    @Column(name = "last_error", columnDefinition = "TEXT")
    private String lastError;

    /** Fleet upgrade staggered rollout 의 wave 분류 (default GENERAL). */
    @Enumerated(EnumType.STRING)
    @Column(name = "upgrade_wave", length = 16, nullable = false)
    @Builder.Default
    private com.aipaas.anycloud.domain.agent.model.ClusterAgentUpgradeWave upgradeWave =
            com.aipaas.anycloud.domain.agent.model.ClusterAgentUpgradeWave.GENERAL;

    /** 진행 중 upgrade 의 상태 (default IDLE). */
    @Enumerated(EnumType.STRING)
    @Column(name = "upgrade_status", length = 16, nullable = false)
    @Builder.Default
    private com.aipaas.anycloud.domain.agent.model.ClusterAgentUpgradeStatus upgradeStatus =
            com.aipaas.anycloud.domain.agent.model.ClusterAgentUpgradeStatus.IDLE;

    @Column(name = "upgrade_target_image", length = 256)
    private String upgradeTargetImage;

    @Column(name = "upgrade_source_version", length = 32)
    private String upgradeSourceVersion;

    @Column(name = "upgrade_started_at")
    private LocalDateTime upgradeStartedAt;

    @Column(name = "upgrade_completed_at")
    private LocalDateTime upgradeCompletedAt;

    @Column(name = "upgrade_error", columnDefinition = "TEXT")
    private String upgradeError;

    // mTLS 제거. cert_serial, cert_expires_at, previous_cert_serial,
    // previous_cert_expires_at column 폐기.  Bearer (identity_token) 단일 인증.

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;
}
