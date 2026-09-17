package com.aipaas.anycloud.domain.provisioning.command;

import static org.assertj.core.api.Assertions.assertThat;

import com.aipaas.anycloud.domain.provisioning.VmClusterEntity;
import com.aipaas.anycloud.domain.provisioning.model.VmClusterStatus;
import com.aipaas.anycloud.testsupport.AbstractUnitTest;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * 같은 이름의 옛 세대까지 정리한다.
 *
 * <p>삭제가 이름 기준 최신 1건만 봐서, 재시도로 쌓인 옛 FAILED 행은 영영 지울 수 없었다.
 * 목록에는 계속 보이는데 삭제 버튼이 아무 일도 하지 않는다.
 */
class DeleteAllGenerationsTest extends AbstractUnitTest {

    private VmClusterEntity row(String id, VmClusterStatus status, String stackName, int minutesAgo) {
        VmClusterEntity e = new VmClusterEntity();
        e.setId(id);
        e.setClusterName("demo");
        e.setProvisioningStatus(status);
        e.setStackName(stackName);
        e.setCreatedAt(LocalDateTime.now().minusMinutes(minutesAgo));
        return e;
    }

    @Test
    void picksEveryGenerationThatIsNotDeletedYet() {
        List<VmClusterEntity> rows = List.of(
                row("newest", VmClusterStatus.DELETED, "stack", 1),
                row("old-failed", VmClusterStatus.FAILED, "stack", 100),
                row("older-failed", VmClusterStatus.FAILED, "stack", 200));

        assertThat(VmClusterDeletionTargets.pending(rows))
                .extracting(VmClusterEntity::getId)
                .containsExactlyInAnyOrder("old-failed", "older-failed");
    }

    @Test
    void alreadyDeletedRowsAreNotTargets() {
        // 이미 지운 것을 다시 destroy 하면 Pulumi 가 없는 스택을 찾다 실패한다.
        assertThat(VmClusterDeletionTargets.pending(List.of(row("a", VmClusterStatus.DELETED, "stack", 1))))
                .isEmpty();
    }

    @Test
    void rowsBeingDeletedAreNotRestarted() {
        // 진행 중인 destroy 를 다시 걸면 같은 스택에 두 번 붙는다.
        assertThat(VmClusterDeletionTargets.pending(List.of(row("a", VmClusterStatus.DELETING, "stack", 1))))
                .isEmpty();
    }

    @Test
    void rowsWithoutAStackAreRemovedOutright() {
        // 스택이 없으면 지울 인프라도 없다. destroy 를 돌릴 이유가 없다.
        assertThat(VmClusterDeletionTargets.needsDestroy(row("a", VmClusterStatus.FAILED, null, 10)))
                .isFalse();
        assertThat(VmClusterDeletionTargets.needsDestroy(row("b", VmClusterStatus.FAILED, "stack", 10)))
                .isTrue();
    }

    @Test
    void blankStackNameCountsAsNoStack() {
        assertThat(VmClusterDeletionTargets.needsDestroy(row("a", VmClusterStatus.FAILED, "  ", 10)))
                .isFalse();
    }

    @Test
    void rowsStuckInDeletingCanBeRedriven() {
        // destroy 가 죽으면 DELETING 인 채로 남는다. 다시 삭제를 눌러도 아무 일이 없으면
        // 사용자는 지울 방법이 없다. 중복 실행은 ProcessingLock 이 막는다.
        List<VmClusterEntity> rows = List.of(row("stuck", VmClusterStatus.DELETING, "stack", 100));

        assertThat(VmClusterDeletionTargets.needsDestroyRun(rows)).isTrue();
    }

    @Test
    void nothingToRunWhenEverythingIsDone() {
        List<VmClusterEntity> rows = List.of(row("done", VmClusterStatus.DELETED, "stack", 100));

        assertThat(VmClusterDeletionTargets.needsDestroyRun(rows)).isFalse();
    }

    @Test
    void rowsWithoutAStackDoNotTriggerARun() {
        // 지울 인프라가 없으면 destroy 를 돌릴 이유가 없다.
        List<VmClusterEntity> rows = List.of(row("a", VmClusterStatus.FAILED, null, 10));

        assertThat(VmClusterDeletionTargets.needsDestroyRun(rows)).isFalse();
    }
}
