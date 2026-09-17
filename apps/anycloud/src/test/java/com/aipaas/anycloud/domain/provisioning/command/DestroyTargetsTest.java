package com.aipaas.anycloud.domain.provisioning.command;

import static org.assertj.core.api.Assertions.assertThat;

import com.aipaas.anycloud.domain.provisioning.VmClusterEntity;
import com.aipaas.anycloud.domain.provisioning.model.VmClusterStatus;
import com.aipaas.anycloud.testsupport.AbstractUnitTest;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * destroy 가 끝나면 그 이름의 DELETING 세대가 모두 닫혀야 한다.
 *
 * <p>같은 이름의 여러 세대를 DELETING 으로 바꿔놓고 destroy 는 최신 1건만 닫았다. 최신 행이 이미
 * DELETED 면 멱등 가드에 걸려 아무것도 하지 않아, 옛 행이 DELETING 에 영원히 남았다.
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
        // READY 인 세대를 함께 닫으면 살아 있는 클러스터가 사라진다.
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
        // 메시지가 최신 행을 가리키는데 그게 이미 DELETED 면 워크플로 가드가 건너뛴다.
        // 그러면 DELETING 인 옛 행은 영영 닫히지 않는다.
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
