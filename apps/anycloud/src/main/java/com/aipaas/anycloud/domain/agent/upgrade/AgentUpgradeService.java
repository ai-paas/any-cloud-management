package com.aipaas.anycloud.domain.agent.upgrade;

/** Single-cluster agent upgrade trigger. */
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
