package com.aipaas.anycloud.domain.agent.upgrade;

/**
 * Fleet upgrade run 의 lifecycle. 단일 run = 운영자가 한 번 trigger 한 fleet-wide upgrade.
 *
 * <pre>
 *   PLANNED  ──┐
 *              ▼
 *           RUNNING ───→ COMPLETED (모든 wave success)
 *              │
 *              ├──→ PAUSED (운영자 수동 중단 — 현재 wave 끝까지 처리 후 정지)
 *              │     │
 *              │     └──→ RUNNING (운영자 resume)  /  ABORTED (운영자 abort)
 *              │
 *              └──→ ABORTED (failure rate 초과 자동 abort)
 * </pre>
 */
public enum FleetUpgradeRunStatus {
    PLANNED,
    RUNNING,
    PAUSED,
    COMPLETED,
    ABORTED;

    public boolean isTerminal() {
        return this == COMPLETED || this == ABORTED;
    }

    public boolean isActive() {
        return this == PLANNED || this == RUNNING;
    }
}
