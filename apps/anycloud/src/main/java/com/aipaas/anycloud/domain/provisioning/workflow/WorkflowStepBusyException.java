package com.aipaas.anycloud.domain.provisioning.workflow;

/**
 * 동일 클러스터의 다른 단계가 소유권 보유 중.
 *
 * <p>일시적 상황이며 메시지 폐기 사유 아님. 앞 단계가 락을 보유한 채 다음 단계를 발행하므로
 * 다음 단계 도착 시점에 항상 충돌. 무음 반환 시 ack 되어 소실되고 워크플로 정지.
 */
public class WorkflowStepBusyException extends RuntimeException {

    public WorkflowStepBusyException(String clusterName, VmClusterWorkflowStep step) {
        super("다른 단계가 처리 중이라 " + step + " 을 되돌린다 (cluster=" + clusterName + ")");
    }
}
