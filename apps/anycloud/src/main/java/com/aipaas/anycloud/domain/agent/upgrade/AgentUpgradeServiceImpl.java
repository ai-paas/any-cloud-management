package com.aipaas.anycloud.domain.agent.upgrade;

import com.aipaas.anycloud.common.error.enums.ErrorCode;
import com.aipaas.anycloud.common.error.exception.CustomException;
import com.aipaas.anycloud.domain.agent.AgentProperties;
import com.aipaas.anycloud.domain.agent.ClusterAgentEntity;
import com.aipaas.anycloud.domain.agent.ClusterAgentRepository;
import com.aipaas.anycloud.domain.agent.model.ClusterAgentStatus;
import com.aipaas.anycloud.domain.agent.model.ClusterAgentUpgradeStatus;
import com.aipaas.anycloud.domain.kube.KubeService;
import java.time.LocalDateTime;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * cluster 한 곳의 agent upgrade 를 trigger 합니다.
 *
 * <p>image 만 바꾼 Deployment manifest 를 apply 하고 끝냅니다. 성공 판정은 여기서 하지 않습니다.
 * 새 pod 가 떠서 heartbeat 로 새 {@code agent_version} 을 보고하면
 * {@link AgentUpgradeProgressMonitor} 가 SUCCEEDED 로 바꾸고, 60분 안에 못 보면 FAILED 로 둡니다.
 *
 * <p>HA 로 row 가 여럿이면 upgrade 메타는 첫 row 에만 씁니다. {@code agent_version} 은 row 마다
 * 각자의 heartbeat 가 갱신합니다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AgentUpgradeServiceImpl implements AgentUpgradeService {

    private final ClusterAgentRepository clusterAgentRepository;
    private final KubeService kubeService;
    /** agent.manifest.* 3개 @Value → AgentProperties 단일 진입점. */
    private final AgentProperties agentProperties;

    private String agentNamespace() {
        return agentProperties.manifest().namespace();
    }

    private String agentDeploymentName() {
        return agentProperties.manifest().deploymentName();
    }

    private String agentContainerName() {
        return agentProperties.manifest().containerName();
    }

    /**
     * 단일 cluster 의 agent upgrade trigger.
     *
     * @throws CustomException {@code ENTITY_NOT_FOUND} cluster 의 agent row 가 0 건이거나
     *                           ACTIVE row 가 없음 (upgrade 할 대상 없음).
     * @throws CustomException {@code CONFLICT} 이미 진행 중인 upgrade 가 있어서 동시 trigger 거부.
     * @throws CustomException {@code AGENT_UNAVAILABLE} APPLY_MANIFEST 호출이 agent 에서 실패.
     */
    @Transactional
    @Override
    @com.aipaas.anycloud.domain.audit.Audited(
            action = "agentUpgrade.cluster",
            resourceType = "clusterAgent",
            resourceId = "#clusterName",
            summary = "'targetImage=' + #targetImage + ', status=' + #result?.status()")
    public UpgradeResult upgradeCluster(String clusterName, String targetImage) {
        if (clusterName == null || clusterName.isBlank()) {
            throw new CustomException("clusterName required", ErrorCode.INVALID_INPUT_VALUE);
        }
        if (targetImage == null || targetImage.isBlank()) {
            throw new CustomException("targetImage required", ErrorCode.INVALID_INPUT_VALUE);
        }

        List<ClusterAgentEntity> rows = clusterAgentRepository.findByClusterName(clusterName);
        if (rows.isEmpty()) {
            throw new CustomException("No agent rows for cluster " + clusterName, ErrorCode.ENTITY_NOT_FOUND);
        }
        // ACTIVE 한 row 가 1개 이상 있어야 upgrade 가능. 모두 비활성이면 운영자가 먼저 cluster 복구해야 함.
        ClusterAgentEntity primary = rows.stream()
                .filter(e -> e.getStatus() == ClusterAgentStatus.ACTIVE)
                .findFirst()
                .orElseThrow(() -> new CustomException(
                        "No ACTIVE agent for cluster " + clusterName + " — fix connectivity first",
                        ErrorCode.AGENT_NOT_ACTIVE));

        // 동시 trigger 방지 — 진행 중이면 거부.
        if (!primary.getUpgradeStatus().isTerminal()) {
            throw new CustomException(
                    "Upgrade already in flight (status=" + primary.getUpgradeStatus() + ", target="
                            + primary.getUpgradeTargetImage() + ")",
                    ErrorCode.UPGRADE_IN_PROGRESS);
        }

        // 같은 image 면 no-op. (HA replica 중 한 row 라도 동일 version 이면 ok 로 간주)
        if (rows.stream().anyMatch(r -> equalsImage(r.getAgentVersion(), targetImage))) {
            log.info("upgrade no-op: cluster {} already running {}", clusterName, targetImage);
            return new UpgradeResult(clusterName, targetImage, "NO_OP", "Cluster already running target image");
        }

        // 1) Mark PENDING + source_version 스냅샷.
        LocalDateTime now = LocalDateTime.now();
        primary.setUpgradeStatus(ClusterAgentUpgradeStatus.PENDING);
        primary.setUpgradeTargetImage(targetImage);
        primary.setUpgradeSourceVersion(primary.getAgentVersion());
        primary.setUpgradeStartedAt(now);
        primary.setUpgradeCompletedAt(null);
        primary.setUpgradeError(null);
        clusterAgentRepository.save(primary);

        // 2) Build minimal Deployment patch + APPLY_MANIFEST via agent.
        String manifest = buildImagePatchManifest(targetImage);
        try {
            kubeService.applyResource(clusterName, agentNamespace(), manifest);
        } catch (Exception e) {
            // agent path 실패 — 즉시 FAILED 로 전환 (운영자 재시도 가능하도록 terminal state).
            primary.setUpgradeStatus(ClusterAgentUpgradeStatus.FAILED);
            primary.setUpgradeCompletedAt(LocalDateTime.now());
            primary.setUpgradeError("APPLY_MANIFEST failed: " + e.getMessage());
            clusterAgentRepository.save(primary);
            log.error("upgrade APPLY failed cluster={} target={}: {}", clusterName, targetImage, e.getMessage());
            throw new CustomException("Agent upgrade failed: " + e.getMessage(), ErrorCode.AGENT_UNAVAILABLE);
        }

        // 3) Mark IN_PROGRESS — 이제 K8s rolling update + 새 pod heartbeat 대기.
        primary.setUpgradeStatus(ClusterAgentUpgradeStatus.IN_PROGRESS);
        clusterAgentRepository.save(primary);

        log.info(
                "upgrade triggered cluster={} target={} source={}",
                clusterName,
                targetImage,
                primary.getUpgradeSourceVersion());
        return new UpgradeResult(
                clusterName, targetImage, "IN_PROGRESS", "Manifest applied. Monitor agent_version via heartbeat.");
    }

    /**
     * Strategic-merge 호환 minimal Deployment patch. K8s 가 spec.template.spec.containers[name=agent]
     * 의 image 만 갱신하고 나머지 (env / volumes / resources / ...) 는 보존.
     *
     * <p>{@code apiVersion} + {@code kind} + {@code metadata.name/namespace} 는 server-side apply
     * 가 식별자로 사용. {@code spec.selector} 와 {@code spec.template.metadata.labels} 도 필수 —
     * Deployment 가 immutable 한 selector 를 strategic merge 에서 검증하므로 동일 값 유지.
     */
    private String buildImagePatchManifest(String targetImage) {
        return String.format(
                """
				apiVersion: apps/v1
				kind: Deployment
				metadata:
				  name: %s
				  namespace: %s
				spec:
				  template:
				    spec:
				      containers:
				        - name: %s
				          image: %s
				""",
                agentDeploymentName(), agentNamespace(), agentContainerName(), targetImage);
    }

    /** agent_version 이 image 의 tag 부분과 일치하는지 단순 비교. */
    private static boolean equalsImage(String currentVersion, String targetImage) {
        if (currentVersion == null || targetImage == null) {
            return false;
        }
        int colonIdx = targetImage.lastIndexOf(':');
        String tag =
                colonIdx > 0 && colonIdx < targetImage.length() - 1 ? targetImage.substring(colonIdx + 1) : targetImage;
        return currentVersion.equals(tag);
    }
}
