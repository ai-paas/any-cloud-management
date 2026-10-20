package com.aipaas.anycloud.domain.provisioning.command;

import static org.assertj.core.api.Assertions.assertThat;

import com.aipaas.anycloud.domain.provisioning.VmClusterEntity;
import com.aipaas.anycloud.domain.provisioning.model.VmClusterStatus;
import com.aipaas.anycloud.testsupport.AbstractUnitTest;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * 기록만 제거하는 강제 삭제.
 *
 * <p>참조 자격증명이 삭제되면 destroy 가 인증에 실패해 행이 잔존. 인프라가 생성되지 않은 경우도
 * 동일. 강제 삭제는 기록만 제거하므로 <b>클라우드에 자원이 잔존할 수 있음</b>. 해당 사실을
 * 응답에 포함해야 사용자가 직접 확인 가능.
 */
class ForceDeleteTest extends AbstractUnitTest {

    private VmClusterEntity row(String id, VmClusterStatus status, String stackName) {
        VmClusterEntity e = new VmClusterEntity();
        e.setId(id);
        e.setClusterName("demo");
        e.setProvisioningStatus(status);
        e.setStackName(stackName);
        return e;
    }

    @Test
    void warnsAboutStacksThatMayStillExist() {
        List<VmClusterEntity> rows =
                List.of(row("a", VmClusterStatus.FAILED, "stack-1"), row("b", VmClusterStatus.DELETING, "stack-1"));

        assertThat(VmClusterDeletionTargets.orphanedStacks(rows)).containsExactly("stack-1");
    }

    @Test
    void doesNotWarnWhenThereWasNoStack() {
        // 스택 부재 시 생성된 자원도 부재. 경고 대상 없음.
        List<VmClusterEntity> rows =
                List.of(row("a", VmClusterStatus.FAILED, null), row("b", VmClusterStatus.FAILED, "  "));

        assertThat(VmClusterDeletionTargets.orphanedStacks(rows)).isEmpty();
    }

    @Test
    void doesNotWarnForRowsAlreadyDestroyed() {
        // DELETED 는 destroy 완료 행. 잔존 자원 없음.
        List<VmClusterEntity> rows = List.of(row("a", VmClusterStatus.DELETED, "stack-1"));

        assertThat(VmClusterDeletionTargets.orphanedStacks(rows)).isEmpty();
    }

    @Test
    void reportsEachStackOnce() {
        // 같은 이름의 세대들은 스택을 공유한다. 같은 경고를 여러 번 내면 읽기 어렵다.
        List<VmClusterEntity> rows = List.of(
                row("a", VmClusterStatus.FAILED, "stack-1"),
                row("b", VmClusterStatus.FAILED, "stack-1"),
                row("c", VmClusterStatus.FAILED, "stack-2"));

        assertThat(VmClusterDeletionTargets.orphanedStacks(rows)).containsExactlyInAnyOrder("stack-1", "stack-2");
    }

    @Test
    void resultCountsRecordsNotStacks() {
        // 세대가 여럿이면 지운 기록 수와 스택 수가 다르다. 스택 수를 기록 수로 보고하면
        // 사용자가 삭제 대상을 오인.
        var result = new ForceDeleteResult(6, List.of("stack-1"));

        assertThat(result.removedRecords()).isEqualTo(6);
        assertThat(result.orphanedStacks()).hasSize(1);
    }
}
