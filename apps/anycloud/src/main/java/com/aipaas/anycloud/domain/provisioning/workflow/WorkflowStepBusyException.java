package com.aipaas.anycloud.domain.provisioning.workflow;

/**
 * 같은 클러스터의 다른 단계가 아직 붙잡고 있다.
 *
 * <p>일시적인 상황이지 메시지를 버릴 이유가 아니다. 앞 단계는 자기 락을 쥔 채 다음 단계를
 * 발행하므로, 다음 단계가 그 순간 도착하면 늘 여기에 걸린다. 조용히 반환하면 ack 되어 사라지고
 * 워크플로가 그 자리에 멈춘다.
 */
public class WorkflowStepBusyException extends RuntimeException {

    public WorkflowStepBusyException(String clusterName, VmClusterWorkflowStep step) {
        super("다른 단계가 처리 중이라 " + step + " 을 되돌린다 (cluster=" + clusterName + ")");
    }
}
