package com.aipaas.anycloud.domain.provisioning.workflow;

import com.aipaas.anycloud.domain.provisioning.model.VmClusterStatus;

public enum VmClusterWorkflowStep {
    PROVISION,
    BOOTSTRAP,
    VERIFY,
    DESTROY;

    /**
     * 현재 클러스터 상태가 이 step 보다 이미 앞서 있는지 판단.
     * 동일 step 이 중복 도착했거나 이전 단계 메시지가 늦게 도착한 경우 스킵 결정에 사용.
     * <p>
     * 멱등성 가드(orchestrator + step service)와 메시지 가드({@code WorkflowMessageGuard})
     * 양쪽에서 동일 기준을 공유하기 위한 단일 출처다.
     */
    public boolean isStaleForStatus(VmClusterStatus status) {
        if (status == null) {
            return false;
        }
        // BLOCKED: 재시도 임계 초과로 자동 진행이 정지된 상태. 모든 step 메시지를 차단해야
        // 운영자의 명시적 retry/destroy 결정 전까지 무한 재시도를 막는다.
        if (status == VmClusterStatus.BLOCKED) {
            return true;
        }
        return switch (this) {
            case PROVISION -> status == VmClusterStatus.BOOTSTRAPPING
                    || status == VmClusterStatus.VERIFYING
                    || status == VmClusterStatus.READY
                    || status == VmClusterStatus.DELETING
                    || status == VmClusterStatus.DELETED;
            case BOOTSTRAP -> status == VmClusterStatus.VERIFYING
                    || status == VmClusterStatus.READY
                    || status == VmClusterStatus.DELETING
                    || status == VmClusterStatus.DELETED;
            case VERIFY -> status == VmClusterStatus.READY
                    || status == VmClusterStatus.DELETING
                    || status == VmClusterStatus.DELETED;
            case DESTROY -> status == VmClusterStatus.DELETED;
        };
    }
}
