package com.aipaas.anycloud.domain.provisioning.workflow;

import static org.assertj.core.api.Assertions.assertThat;

import com.aipaas.anycloud.domain.provisioning.model.VmClusterStatus;
import com.aipaas.anycloud.testsupport.AbstractUnitTest;
import org.junit.jupiter.api.Test;

/**
 * 세대 가드가 막아야 하는 것과 막으면 안 되는 것.
 *
 * <p>가드의 목적은 <b>부활 방지</b>다 — 재시작으로 재전달된 옛 메시지가 이미 지운 클러스터를
 * 다시 만드는 사고를 막는다. DESTROY 는 반대 방향이라 막을 이유가 없고, 막으면 같은 이름의 옛
 * 세대를 지울 방법이 사라진다.
 */
class SupersededGuardTest extends AbstractUnitTest {

    @Test
    void createStepsOnAnOldGenerationAreBlocked() {
        // 옛 메시지가 pulumi up 까지 실행해 삭제된 클러스터를 되살린 적이 있다.
        assertThat(SupersededPolicy.blocks(VmClusterWorkflowStep.PROVISION, VmClusterStatus.FAILED))
                .isTrue();
        assertThat(SupersededPolicy.blocks(VmClusterWorkflowStep.BOOTSTRAP, VmClusterStatus.FAILED))
                .isTrue();
        assertThat(SupersededPolicy.blocks(VmClusterWorkflowStep.VERIFY, VmClusterStatus.FAILED))
                .isTrue();
    }

    @Test
    void destroyOnARowBeingDeletedIsAllowed() {
        // 사용자가 명시적으로 지우라고 한 행이다. 막으면 지울 방법이 없다.
        assertThat(SupersededPolicy.blocks(VmClusterWorkflowStep.DESTROY, VmClusterStatus.DELETING))
                .isFalse();
    }

    @Test
    void destroyOnARowNobodyAskedToDeleteIsStillBlocked() {
        // DELETING 이 아니면 누가 시킨 삭제가 아니다. 잔존 메시지일 수 있다.
        assertThat(SupersededPolicy.blocks(VmClusterWorkflowStep.DESTROY, VmClusterStatus.READY))
                .isTrue();
        assertThat(SupersededPolicy.blocks(VmClusterWorkflowStep.DESTROY, VmClusterStatus.FAILED))
                .isTrue();
    }
}
