package com.aipaas.anycloud.domain.provisioning.workflow;

import static org.assertj.core.api.Assertions.assertThat;

import com.aipaas.anycloud.domain.provisioning.model.VmClusterStatus;
import com.aipaas.anycloud.testsupport.AbstractUnitTest;
import org.junit.jupiter.api.Test;

/**
 * 세대 가드가 막아야 하는 것과 막으면 안 되는 것.
 *
 * <p>가드의 목적은 <b>재생성 방지</b>. 재시작으로 재전달된 이전 메시지가 삭제된 클러스터를
 * 다시 생성하는 상황 차단. DESTROY 는 반대 방향이라 차단 불필요하며, 차단 시 동일 이름의
 * 이전 세대 삭제 수단 소멸.
 */
class SupersededGuardTest extends AbstractUnitTest {

    @Test
    void createStepsOnAnOldGenerationAreBlocked() {
        // 이전 메시지가 pulumi up 까지 실행해 삭제된 클러스터를 재생성한 사례 발생.
        assertThat(SupersededPolicy.blocks(VmClusterWorkflowStep.PROVISION, VmClusterStatus.FAILED))
                .isTrue();
        assertThat(SupersededPolicy.blocks(VmClusterWorkflowStep.BOOTSTRAP, VmClusterStatus.FAILED))
                .isTrue();
        assertThat(SupersededPolicy.blocks(VmClusterWorkflowStep.VERIFY, VmClusterStatus.FAILED))
                .isTrue();
    }

    @Test
    void destroyOnARowBeingDeletedIsAllowed() {
        // 사용자가 명시적으로 삭제를 요청한 행. 차단 시 삭제 수단 부재.
        assertThat(SupersededPolicy.blocks(VmClusterWorkflowStep.DESTROY, VmClusterStatus.DELETING))
                .isFalse();
    }

    @Test
    void destroyOnARowNobodyAskedToDeleteIsStillBlocked() {
        // DELETING 이 아니면 요청된 삭제가 아님. 잔존 메시지 가능성.
        assertThat(SupersededPolicy.blocks(VmClusterWorkflowStep.DESTROY, VmClusterStatus.READY))
                .isTrue();
        assertThat(SupersededPolicy.blocks(VmClusterWorkflowStep.DESTROY, VmClusterStatus.FAILED))
                .isTrue();
    }
}
