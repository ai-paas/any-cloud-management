package com.aipaas.anycloud.domain.provisioning.command;

import com.aipaas.anycloud.domain.provisioning.VmClusterEntity;
import com.aipaas.anycloud.domain.provisioning.model.VmClusterStatus;
import java.util.List;

/**
 * 삭제 대상 고르기.
 *
 * <p>삭제가 이름 기준 최신 1건만 봐서, 재시도로 쌓인 옛 FAILED 행은 영영 지울 수 없었다. 목록에는
 * 계속 보이는데 삭제 버튼이 아무 일도 하지 않는다. 같은 이름은 한 클러스터의 세대들이므로 함께
 * 정리한다.
 */
public final class VmClusterDeletionTargets {

    private VmClusterDeletionTargets() {}

    /** 아직 정리되지 않은 세대. 이미 끝났거나 진행 중인 것은 건드리지 않는다. */
    public static List<VmClusterEntity> pending(List<VmClusterEntity> rows) {
        return rows.stream()
                .filter(row -> row.getProvisioningStatus() != VmClusterStatus.DELETED)
                .filter(row -> row.getProvisioningStatus() != VmClusterStatus.DELETING)
                .toList();
    }

    /**
     * 지금 삭제 중인 세대.
     *
     * <p>destroy 는 스택 하나만 지우면 되지만, 그 이름의 DELETING 행은 전부 닫아야 한다.
     * 최신 1건만 닫으면 옛 행이 DELETING 에 영원히 남는다.
     */
    public static List<VmClusterEntity> beingDeleted(List<VmClusterEntity> rows) {
        return rows.stream()
                .filter(row -> row.getProvisioningStatus() == VmClusterStatus.DELETING)
                .toList();
    }

    /**
     * destroy 를 걸어야 하는지.
     *
     * <p>아직 DELETING 으로 못 간 세대뿐 아니라, 이미 DELETING 인데 멈춘 세대도 대상이다.
     * destroy 가 죽으면 DELETING 인 채로 남는데, 다시 삭제를 눌러도 아무 일이 없으면 사용자는
     * 지울 방법이 없다. 중복 실행은 ProcessingLock 이 막는다.
     */
    public static boolean needsDestroyRun(List<VmClusterEntity> rows) {
        return rows.stream()
                .filter(row -> row.getProvisioningStatus() != VmClusterStatus.DELETED)
                .anyMatch(VmClusterDeletionTargets::needsDestroy);
    }

    /**
     * destroy 메시지가 가리킬 세대.
     *
     * <p>최신 행을 가리키는데 그게 이미 DELETED 면 워크플로 가드가 메시지를 건너뛴다. 그러면
     * DELETING 인 옛 행은 영영 닫히지 않는다. 삭제 중인 행이 있으면 그쪽을 가리킨다.
     *
     * @param rows 최신순으로 정렬된 같은 이름의 세대들
     */
    public static VmClusterEntity destroyMessageTarget(List<VmClusterEntity> rows) {
        return beingDeleted(rows).stream().findFirst().orElseGet(() -> rows.get(0));
    }

    /**
     * 강제 삭제로 기록을 지울 때, 클라우드에 남아 있을 수 있는 스택.
     *
     * <p>강제 삭제는 destroy 를 돌리지 않는다. 스택이 있었던 행은 자원이 남아 있을 수 있으므로
     * 어떤 스택인지 알려야 사용자가 직접 확인할 수 있다. 이미 DELETED 인 행은 destroy 가 끝난
     * 것이라 제외한다.
     */
    public static List<String> orphanedStacks(List<VmClusterEntity> rows) {
        return rows.stream()
                .filter(row -> row.getProvisioningStatus() != VmClusterStatus.DELETED)
                .filter(VmClusterDeletionTargets::needsDestroy)
                .map(VmClusterEntity::getStackName)
                .distinct()
                .toList();
    }

    /** 스택이 없으면 지울 인프라도 없다. destroy 를 돌릴 이유가 없다. */
    public static boolean needsDestroy(VmClusterEntity row) {
        String stackName = row.getStackName();
        return stackName != null && !stackName.isBlank();
    }
}
