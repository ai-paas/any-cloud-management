package com.aipaas.anycloud.domain.provisioning.workflow;

import com.aipaas.anycloud.domain.provisioning.VmClusterEntity;
import com.aipaas.anycloud.domain.provisioning.model.VmClusterStatus;
import java.time.Duration;
import java.time.LocalDateTime;

/**
 * 진행 중인 채로 멈춘 단계를 다시 밀지 판단한다.
 *
 * <p>메시지를 즉시 ack 하면 크래시 때 되돌아올 것이 없다. 이 규칙이 즉시 ack 의 안전망이고,
 * 없으면 재전달 루프를 메시지 유실로 바꾼 것에 지나지 않는다.
 */
public final class StuckStepRules {

    private StuckStepRules() {}

    /** 상태가 이 중 하나면 아직 워크플로가 붙어 있어야 한다. */
    public static VmClusterWorkflowStep stepFor(VmClusterStatus status) {
        if (status == null) {
            return null;
        }
        return switch (status) {
            case PROVISIONING -> VmClusterWorkflowStep.PROVISION;
            case BOOTSTRAPPING -> VmClusterWorkflowStep.BOOTSTRAP;
            case VERIFYING -> VmClusterWorkflowStep.VERIFY;
            case DELETING -> VmClusterWorkflowStep.DESTROY;
                // 끝난 것을 다시 밀면 멀쩡한 클러스터를 부트스트랩한다.
            default -> null;
        };
    }

    /**
     * @param staleAfter 단계의 최악 예산. 이보다 오래 붙잡고 있으면 진행이 아니라 멈춘 것으로 본다
     */
    public static boolean needsRedrive(VmClusterEntity cluster, LocalDateTime now, Duration staleAfter) {
        if (cluster == null || stepFor(cluster.getProvisioningStatus()) == null) {
            return false;
        }
        LocalDateTime startedAt = cluster.getProcessingStartedAt();
        // 주인이 없는데 진행 중이면 ack 는 됐고 실행은 시작되지 못한 것이다 — 크래시나 풀 거부.
        if (startedAt == null) {
            return true;
        }
        return startedAt.isBefore(now.minus(staleAfter));
    }
}
