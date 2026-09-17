package com.aipaas.anycloud.domain.provisioning.workflow;

import com.aipaas.anycloud.domain.provisioning.model.VmClusterStatus;

/**
 * 옛 세대를 가리키는 메시지를 막을지 결정한다.
 *
 * <p>가드의 목적은 <b>부활 방지</b>다 — 재시작으로 재전달된 옛 메시지가 이미 지운 클러스터를 다시
 * 만드는 사고를 막는다. DESTROY 는 반대 방향이라 막을 이유가 없고, 막으면 같은 이름으로 재시도하며
 * 쌓인 옛 세대를 지울 방법이 사라진다.
 *
 * <p>다만 DELETING 인 행만 통과시킨다. 그 상태는 사용자가 명시적으로 삭제를 건 결과이고, 그렇지
 * 않은 행에 오는 DESTROY 는 잔존 메시지일 수 있다.
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
