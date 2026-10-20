package com.aipaas.anycloud.domain.provisioning.workflow;

import com.aipaas.anycloud.domain.provisioning.VmClusterEntity;
import com.aipaas.anycloud.domain.provisioning.model.VmClusterStatus;
import java.time.Duration;
import java.time.LocalDateTime;

/**
 * 진행 중 상태로 정지한 단계의 재실행 여부 판정.
 *
 * <p>메시지 즉시 ack 구조에서는 크래시 시 RabbitMQ 재전달 없음. 이 규칙이 재실행 대상을 선별.
 */
public final class StuckStepRules {

    private StuckStepRules() {}

    /** 해당 상태 집합은 워크플로 연결이 유지돼야 하는 구간. */
    public static VmClusterWorkflowStep stepFor(VmClusterStatus status) {
        if (status == null) {
            return null;
        }
        return switch (status) {
            case PROVISIONING -> VmClusterWorkflowStep.PROVISION;
            case BOOTSTRAPPING -> VmClusterWorkflowStep.BOOTSTRAP;
            case VERIFYING -> VmClusterWorkflowStep.VERIFY;
            case DELETING -> VmClusterWorkflowStep.DESTROY;
                // 완료된 단계를 재투입하면 정상 클러스터에 부트스트랩이 재실행.
            default -> null;
        };
    }

    /**
     * @param staleAfter 단계 최악 예산. 초과 점유 시 진행이 아닌 정지 상태로 판정
     */
    public static boolean needsRedrive(VmClusterEntity cluster, LocalDateTime now, Duration staleAfter) {
        if (cluster == null || stepFor(cluster.getProvisioningStatus()) == null) {
            return false;
        }
        LocalDateTime startedAt = cluster.getProcessingStartedAt();
        // 주인이 없는데 진행 중이면 ack 는 됐고 실행은 시작되지 못한 것이다. 크래시나 풀 거부.
        if (startedAt == null) {
            return true;
        }
        return startedAt.isBefore(now.minus(staleAfter));
    }
}
