package com.aipaas.anycloud.domain.agent.model;

import java.time.LocalDateTime;

/**
 * Cluster agent 의 immutable 도메인 표현.
 *
 * <p>등록 / heartbeat / upgrade lifecycle 의 모든 state 를 한 record 로 캡처. Bearer-only 인증 모델.
 */
public record ClusterAgent(
        String agentId,
        String clusterName,
        String agentInstanceId,
        String k8sClusterUid,
        String identityTokenHash,
        ClusterAgentStatus status,
        String agentVersion,
        String distribution,
        String k8sVersion,
        String endpoint,
        String publicIp,
        String privateIp,
        String podCidr,
        String serviceCidr,
        LocalDateTime registeredAt,
        LocalDateTime lastSeenAt,
        LocalDateTime lastK8sApiOkAt,
        LocalDateTime expiresAt,
        LocalDateTime revokedAt,
        String lastError,
        ClusterAgentUpgradeWave upgradeWave,
        ClusterAgentUpgradeStatus upgradeStatus,
        String upgradeTargetImage,
        String upgradeSourceVersion,
        LocalDateTime upgradeStartedAt,
        LocalDateTime upgradeCompletedAt,
        String upgradeError,
        LocalDateTime createdAt,
        LocalDateTime updatedAt) {

    /** ACTIVE — stream alive + heartbeat fresh. */
    public boolean isActive() {
        return status == ClusterAgentStatus.ACTIVE && revokedAt == null;
    }

    /** Revoked 또는 expired 로 더 이상 인증 불가. */
    public boolean isInvalidated() {
        return revokedAt != null || (expiresAt != null && expiresAt.isBefore(LocalDateTime.now()));
    }

    /** Upgrade 진행 중 (IDLE 아님). */
    public boolean isUpgrading() {
        return upgradeStatus != null && upgradeStatus != ClusterAgentUpgradeStatus.IDLE;
    }
}
