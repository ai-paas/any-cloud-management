package com.aipaas.anycloud.domain.provisioning.command;

import com.aipaas.anycloud.domain.provisioning.VmClusterEntity;
import com.aipaas.anycloud.domain.provisioning.model.VmClusterStatus;
import java.util.List;

/**
 * 삭제 대상 선정.
 *
 * <p>삭제가 이름 기준 최신 1건만 처리하면 재시도로 누적된 이전 FAILED 행은 제거 불가. 목록에는
 * 계속 표시되나 삭제가 동작하지 않음. 동일 이름은 한 클러스터의 세대이므로 함께 정리.
 */
public final class VmClusterDeletionTargets {

    private VmClusterDeletionTargets() {}

    /** 미정리 세대. 완료 또는 진행 중인 세대는 제외. */
    public static List<VmClusterEntity> pending(List<VmClusterEntity> rows) {
        return rows.stream()
                .filter(row -> row.getProvisioningStatus() != VmClusterStatus.DELETED)
                .filter(row -> row.getProvisioningStatus() != VmClusterStatus.DELETING)
                .toList();
    }

    /**
     * 삭제 진행 중인 세대.
     *
     * <p>destroy 대상 스택은 하나지만 해당 이름의 DELETING 행은 전부 종료 처리 필요. 최신 1건만
     * 처리하면 이전 행이 DELETING 에 잔존.
     */
    public static List<VmClusterEntity> beingDeleted(List<VmClusterEntity> rows) {
        return rows.stream()
                .filter(row -> row.getProvisioningStatus() == VmClusterStatus.DELETING)
                .toList();
    }

    /**
     * destroy 실행 필요 여부.
     *
     * <p>DELETING 미진입 세대와 DELETING 상태로 정지한 세대 모두 대상. destroy 종료 시 DELETING
     * 상태로 잔존하는데 재삭제가 동작하지 않으면 제거 수단 부재. 중복 실행은 ProcessingLock 이 차단.
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
     * 강제 삭제로 기록 제거 시 클라우드에 잔존 가능한 스택.
     *
     * <p>강제 삭제는 destroy 미실행. 스택이 존재했던 행은 자원 잔존 가능성이 있어 스택 식별자
     * 전달 필요. DELETED 행은 destroy 완료 상태이므로 제외.
     */
    public static List<String> orphanedStacks(List<VmClusterEntity> rows) {
        return rows.stream()
                .filter(row -> row.getProvisioningStatus() != VmClusterStatus.DELETED)
                .filter(VmClusterDeletionTargets::needsDestroy)
                .map(VmClusterEntity::getStackName)
                .distinct()
                .toList();
    }

    /** 스택 부재 시 삭제할 인프라도 부재. destroy 실행 불필요. */
    public static boolean needsDestroy(VmClusterEntity row) {
        String stackName = row.getStackName();
        return stackName != null && !stackName.isBlank();
    }
}
