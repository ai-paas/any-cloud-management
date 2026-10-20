package com.aipaas.anycloud.domain.provisioning.command;

import static org.assertj.core.api.Assertions.assertThat;

import com.aipaas.anycloud.domain.provisioning.VmClusterEntity;
import com.aipaas.anycloud.domain.provisioning.model.VmClusterStatus;
import com.aipaas.anycloud.testsupport.AbstractUnitTest;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * 동일 이름의 이전 세대까지 정리.
 *
 * <p>삭제가 이름 기준 최신 1건만 처리하면 재시도로 누적된 이전 FAILED 행은 제거 불가.
 * 목록에는 계속 표시되나 삭제가 동작하지 않음.
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
        // 삭제 완료 항목의 destroy 재실행 시 Pulumi 가 미존재 스택 조회로 실패.
        assertThat(VmClusterDeletionTargets.pending(List.of(row("a", VmClusterStatus.DELETED, "stack", 1))))
                .isEmpty();
    }

    @Test
    void rowsBeingDeletedAreNotRestarted() {
        // 진행 중인 destroy 재실행 시 동일 스택에 중복 작업.
        assertThat(VmClusterDeletionTargets.pending(List.of(row("a", VmClusterStatus.DELETING, "stack", 1))))
                .isEmpty();
    }

    @Test
    void rowsWithoutAStackAreRemovedOutright() {
        // 스택 부재 시 삭제할 인프라도 부재. destroy 실행 불필요.
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
        // destroy 중단 시 DELETING 상태로 잔존. 재삭제가 동작하지 않으면 제거 수단 부재.
        // 중복 실행은 ProcessingLock 이 차단.
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
        // 삭제할 인프라가 없으면 destroy 실행 불필요.
        List<VmClusterEntity> rows = List.of(row("a", VmClusterStatus.FAILED, null, 10));

        assertThat(VmClusterDeletionTargets.needsDestroyRun(rows)).isFalse();
    }
}
