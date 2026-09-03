package com.aipaas.anycloud.domain.agent.upgrade;

/**
 * Single-cluster agent upgrade trigger.
 *
 * <p>운영자가 단일 cluster 의 agent 를 target image 로 upgrade — 최소 Deployment patch 를
 * agent path ({@code APPLY_MANIFEST}) 로 보내 K8s rolling update 트리거.
 *
 * <p>구현체: {@link AgentUpgradeServiceImpl}. fleet-wide 의 wave 기반 orchestration 은
 * {@link FleetUpgradeOrchestrator} 가 본 interface 의 메서드를 wave 별로 호출.
 *
 * <p>Trigger 직후 IN_PROGRESS 응답 — 진행 감지는 heartbeat 기반으로
 * {@link AgentUpgradeProgressMonitor} 가 SUCCEEDED/FAILED 전환.
 */
public interface AgentUpgradeService {

    /**
     * 단일 cluster 의 agent upgrade trigger.
     *
     * @return {@link UpgradeResult} — status 가 {@code IN_PROGRESS} 또는 {@code NO_OP} (이미 target image)
     * @throws com.aipaas.anycloud.common.error.exception.CustomException
     *         {@code AGENT_NOT_ACTIVE} ACTIVE row 없음 / {@code UPGRADE_IN_PROGRESS} 이미 진행 중 /
     *         {@code AGENT_UNAVAILABLE} APPLY_MANIFEST 호출 실패
     */
    UpgradeResult upgradeCluster(String clusterName, String targetImage);

    /** Upgrade trigger 의 결과 응답. */
    record UpgradeResult(String clusterName, String targetImage, String status, String detail) {}
}
