package com.aipaas.anycloud.domain.cluster.model;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * Registered cluster 의 lifecycle status. enum 정의 + canTransitionTo graph + helper.
 *
 * <p>ClusterEntity.status field 는 String — 호출 측이 {@link #name()} 으로 변환. type 안정성은 호출자 책임.
 *
 * <p><b> (future)</b>: ClusterEntity.status field 를 {@code @Enumerated(STRING)} 으로 변경
 * + Flyway migration (varchar(45) → enum). 기존 데이터 값이 모두 enum 정의에 매칭되어야 함.
 *
 * <p><b>State diagram</b>:
 * <pre>
 *  initial (null/empty)
 *      │
 *      ├──▶ AGENT_PENDING (agent-first 등록)
 *      │       │
 *      │       ▼ agent backfill
 *      ├──▶ UNKNOWN (status 모름 — 초기 또는 일시적)
 *      │       │
 *      │       ▼ health check
 *      └──▶ ACTIVE ◄──┐
 *               │      │ K8s API 응답 회복
 *               ▼      │
 *           INACTIVE ──┘
 * </pre>
 *
 * <p>Day-2 운영 중 ACTIVE ↔ INACTIVE 빈번 — connectivity 변동 그대로 반영.
 */
public enum ClusterStatus {

    /** 초기 상태 또는 health 정보 부재. */
    UNKNOWN,

    /** Agent-first 등록 — agent backfill 대기 중. */
    AGENT_PENDING,

    /** K8s API 정상 응답 + agent ACTIVE (또는 fabric8 fallback OK). */
    ACTIVE,

    /** K8s API 응답 실패 또는 agent 끊김. 일시적이거나 영구. */
    INACTIVE;

    /** Active 상태인지. UI / 배포 가능 여부. */
    public boolean isActive() {
        return this == ACTIVE;
    }

    public boolean canTransitionTo(ClusterStatus next) {
        if (next == null) return false;
        if (next == this) return true;
        Set<ClusterStatus> allowed = ALLOWED_TRANSITIONS.get(this);
        return allowed != null && allowed.contains(next);
    }

    private static final Map<ClusterStatus, Set<ClusterStatus>> ALLOWED_TRANSITIONS;

    static {
        Map<ClusterStatus, Set<ClusterStatus>> m = new EnumMap<>(ClusterStatus.class);
        // 모든 transition 매우 lenient — registered cluster 의 health 는 빈번히 변동.
        m.put(UNKNOWN, EnumSet.of(AGENT_PENDING, ACTIVE, INACTIVE));
        m.put(AGENT_PENDING, EnumSet.of(ACTIVE, INACTIVE, UNKNOWN));
        m.put(ACTIVE, EnumSet.of(INACTIVE, UNKNOWN));
        m.put(INACTIVE, EnumSet.of(ACTIVE, UNKNOWN));
        ALLOWED_TRANSITIONS = Map.copyOf(m);
    }

    /** String → enum (null/empty/unknown 값은 UNKNOWN 으로 fallback). */
    public static ClusterStatus fromOrUnknown(String value) {
        if (value == null || value.isBlank()) return UNKNOWN;
        try {
            return ClusterStatus.valueOf(value.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return UNKNOWN;
        }
    }
}
