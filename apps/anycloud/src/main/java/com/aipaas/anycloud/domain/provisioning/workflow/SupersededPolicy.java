package com.aipaas.anycloud.domain.provisioning.workflow;

import com.aipaas.anycloud.domain.provisioning.model.VmClusterStatus;

/**
 * 이전 세대를 지칭하는 메시지의 차단 여부 결정.
 *
 * <p>가드의 목적은 <b>재생성 방지</b>. 재시작으로 재전달된 이전 메시지가 삭제된 클러스터를
 * 다시 생성하는 상황 차단. DESTROY 는 반대 방향이라 차단 불필요하며, 차단 시 동일 이름 재시도로
 * 누적된 이전 세대의 삭제 수단 소멸.
 *
 * <p>단 DELETING 행만 통과. 해당 상태는 사용자의 명시적 삭제 결과이며, 그 외 행에 도착한
 * DESTROY 는 잔존 메시지일 가능성 존재.
 */
public final class SupersededPolicy {

    private SupersededPolicy() {}

    public static boolean blocks(VmClusterWorkflowStep step, VmClusterStatus status) {
        if (step == VmClusterWorkflowStep.DESTROY && status == VmClusterStatus.DELETING) {
            return false;
        }
        return true;
    }
}
