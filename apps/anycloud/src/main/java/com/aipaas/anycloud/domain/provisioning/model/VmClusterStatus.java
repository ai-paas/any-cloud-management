package com.aipaas.anycloud.domain.provisioning.model;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * VM cluster workflow 의 lifecycle state.
 *
 * <p><b>State diagram</b> (자세한 그림: {@code docs/architecture/vmcluster-state-machine.md}):
 *
 * <pre>
 *     [initial=null]
 *           │
 *           ▼ create
 *      REQUESTED
 *           │
 *           ▼ provision start
 *     PROVISIONING ──────┐
 *      │   │  │          │ retry threshold exceeded
 *      │   │  ▼          │
 *      │   │  BLOCKED ◄──┘── operator retry / destroy
 *      │   │
 *      │   ▼ provision ok
 *      │   BOOTSTRAPPING
 *      │     │
 *      │     ▼ bootstrap ok
 *      │   VERIFYING
 *      │     │
 *      │     ▼ verify ok
 *      │   READY ◄──── (scale/upgrade temporarily revert to PROVISIONING)
 *      │     │
 *      │     ▼ delete request
 *      │   DELETING ──▶ DELETED (terminal)
 *      ▼     │
 *    FAILED ◄┘── (manual retry → PROVISIONING)
 * </pre>
 *
 * <p>{@link #canTransitionTo} 가 graph 의 single source of truth.
 * {@code VmClusterEntity.transitionTo} 가 호출 측에서 사용 (validation + logging + metric).
 * Invalid transition 은 default 로 log.warn + metric, strict 모드
 * ({@code anycloud.vm-cluster.state-machine.strict=true}) 에서는 throw.
 */
public enum VmClusterStatus {
    REQUESTED,
    PROVISIONING,
    BOOTSTRAPPING,
    VERIFYING,
    READY,
    /** Day-2 ops — workerCount 변경 진행 중. */
    SCALING,
    /** Day-2 ops — Kubernetes version upgrade 진행 중. */
    UPGRADING,
    FAILED,
    /**
     * 재시도 임계({@code vm-cluster-workflow.max-attempts})를 초과해 자동 진행을 멈춘 상태.
     * 운영자의 명시적 결정(재시도 / 강제 destroy / 진단 후 데이터 보존)이 있을 때까지 워크플로우는 정지.
     */
    BLOCKED,
    DELETING,
    DELETED;

    // ============= State transition graph =============

    /** Terminal — 더 이상 자동 진행 안 함. */
    public boolean isTerminal() {
        return this == DELETED;
    }

    /** Workflow 정지 — 운영자 개입 필요. */
    public boolean isBlocked() {
        return this == BLOCKED;
    }

    /** Active workflow (자동 진행 중). */
    public boolean isInProgress() {
        return this == REQUESTED
                || this == PROVISIONING
                || this == BOOTSTRAPPING
                || this == VERIFYING
                || this == SCALING
                || this == UPGRADING
                || this == DELETING;
    }

    /**
     * 현재 상태에서 {@code next} 로 transition 이 valid 한지 검사.
     *
     * <p>state diagram 의 single source of truth. {@code VmClusterEntity#transitionTo} 가 본
     * 메서드로 검증.
     *
     * @return true 면 valid 또는 idempotent (same state).
     */
    public boolean canTransitionTo(VmClusterStatus next) {
        if (next == null) return false;
        if (next == this) return true; // idempotent re-assignment OK
        Set<VmClusterStatus> allowed = ALLOWED_TRANSITIONS.get(this);
        return allowed != null && allowed.contains(next);
    }

    private static final Map<VmClusterStatus, Set<VmClusterStatus>> ALLOWED_TRANSITIONS;

    static {
        Map<VmClusterStatus, Set<VmClusterStatus>> m = new EnumMap<>(VmClusterStatus.class);
        // Create flow
        m.put(REQUESTED, EnumSet.of(PROVISIONING, FAILED, BLOCKED, DELETING));
        m.put(PROVISIONING, EnumSet.of(BOOTSTRAPPING, READY, FAILED, BLOCKED, DELETING));
        m.put(BOOTSTRAPPING, EnumSet.of(VERIFYING, FAILED, BLOCKED, DELETING));
        m.put(VERIFYING, EnumSet.of(READY, FAILED, BLOCKED, DELETING));
        // Steady state — day-2 ops 가 명시적 status 로 분기.
        m.put(READY, EnumSet.of(SCALING, UPGRADING, FAILED, DELETING));
        // Day-2 ops — 완료 후 READY 복귀, 실패 시 FAILED. delete 도 어디서나 가능.
        m.put(SCALING, EnumSet.of(READY, FAILED, DELETING));
        m.put(UPGRADING, EnumSet.of(READY, FAILED, DELETING));
        // Recovery — operator / auto retry. BOOTSTRAPPING 은 VERIFY 재시도 시 진입 직전 상태로
        // transition 이 필요하다 (command.retry.VERIFY).
        m.put(FAILED, EnumSet.of(PROVISIONING, BOOTSTRAPPING, BLOCKED, DELETING, REQUESTED));
        m.put(BLOCKED, EnumSet.of(PROVISIONING, BOOTSTRAPPING, DELETING, REQUESTED));
        // Delete flow
        m.put(DELETING, EnumSet.of(DELETED, FAILED));
        // Terminal
        m.put(DELETED, EnumSet.noneOf(VmClusterStatus.class));
        ALLOWED_TRANSITIONS = Map.copyOf(m);
    }

    public static VmClusterStatus from(String value) {
        return VmClusterStatus.valueOf(value.trim().toUpperCase());
    }

    public String detailMessage() {
        return switch (this) {
            case REQUESTED -> "생성 요청이 저장되었습니다.";
            case PROVISIONING -> "Pulumi로 VM 인프라를 생성 중입니다.";
            case BOOTSTRAPPING -> "Bootstrap worker가 kubeadm 구성을 진행 중입니다.";
            case VERIFYING -> "클러스터 연결과 등록 상태를 검증 중입니다.";
            case READY -> "VM 클러스터 생성과 등록이 완료되었습니다.";
            case SCALING -> "워커 노드 수를 조정 중입니다.";
            case UPGRADING -> "Kubernetes 버전을 업그레이드 중입니다.";
            case FAILED -> "생성 또는 등록 중 오류가 발생했습니다.";
            case BLOCKED -> "재시도 횟수를 초과해 자동 진행이 정지되었습니다. 운영자의 수동 개입이 필요합니다.";
            case DELETING -> "VM 클러스터와 Pulumi stack을 삭제 중입니다.";
            case DELETED -> "VM 클러스터와 Pulumi stack이 삭제되었습니다.";
        };
    }
}
