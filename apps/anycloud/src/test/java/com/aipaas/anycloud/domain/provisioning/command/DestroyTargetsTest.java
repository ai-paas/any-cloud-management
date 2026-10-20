package com.aipaas.anycloud.domain.provisioning.command;

import static org.assertj.core.api.Assertions.assertThat;

import com.aipaas.anycloud.domain.provisioning.VmClusterEntity;
import com.aipaas.anycloud.domain.provisioning.model.VmClusterStatus;
import com.aipaas.anycloud.testsupport.AbstractUnitTest;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * destroy 완료 시 해당 이름의 DELETING 세대 전체 종료 처리 필요.
 *
 * <p>동일 이름의 여러 세대를 DELETING 으로 전이시킨 뒤 destroy 가 최신 1건만 종료 처리. 최신
 * 행이 이미 DELETED 면 멱등 가드에 걸려 무동작이고, 이전 행이 DELETING 에 잔존.
 */
class DestroyTargetsTest extends AbstractUnitTest {

    private VmClusterEntity row(String id, VmClusterStatus status) {
        VmClusterEntity e = new VmClusterEntity();
        e.setId(id);
        e.setClusterName("demo");
        e.setProvisioningStatus(status);
        e.setStackName("stack");
        return e;
    }

    @Test
    void picksEveryRowBeingDeleted() {
        List<VmClusterEntity> rows = List.of(
                row("done", VmClusterStatus.DELETED),
                row("a", VmClusterStatus.DELETING),
                row("b", VmClusterStatus.DELETING));

        assertThat(VmClusterDeletionTargets.beingDeleted(rows))
                .extracting(VmClusterEntity::getId)
                .containsExactlyInAnyOrder("a", "b");
    }

    @Test
    void doesNotTouchRowsThatAreNotBeingDeleted() {
        // READY 세대를 함께 종료 처리하면 유효 클러스터가 삭제됨.
        List<VmClusterEntity> rows = List.of(row("live", VmClusterStatus.READY), row("a", VmClusterStatus.DELETING));

        assertThat(VmClusterDeletionTargets.beingDeleted(rows))
                .extracting(VmClusterEntity::getId)
                .containsExactly("a");
    }

    @Test
    void emptyWhenNothingIsBeingDeleted() {
        assertThat(VmClusterDeletionTargets.beingDeleted(List.of(row("x", VmClusterStatus.FAILED))))
                .isEmpty();
    }

    @Test
    void destroyMessageTargetsARowThatIsActuallyBeingDeleted() {
        // 메시지가 가리키는 최신 행이 이미 DELETED 면 워크플로 가드가 생략.
        // 이 경우 DELETING 상태의 이전 행은 종료 처리되지 않음.
        List<VmClusterEntity> rows =
                List.of(row("newest-done", VmClusterStatus.DELETED), row("stuck", VmClusterStatus.DELETING));

        assertThat(VmClusterDeletionTargets.destroyMessageTarget(rows))
                .extracting(VmClusterEntity::getId)
                .isEqualTo("stuck");
    }

    @Test
    void fallsBackToTheNewestWhenNothingIsBeingDeleted() {
        List<VmClusterEntity> rows =
                List.of(row("newest", VmClusterStatus.FAILED), row("older", VmClusterStatus.FAILED));

        assertThat(VmClusterDeletionTargets.destroyMessageTarget(rows))
                .extracting(VmClusterEntity::getId)
                .isEqualTo("newest");
    }
}
