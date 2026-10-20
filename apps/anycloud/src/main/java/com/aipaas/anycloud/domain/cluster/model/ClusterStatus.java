package com.aipaas.anycloud.domain.cluster.model;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * 등록된 cluster 의 lifecycle status. 전이 규칙의 단일 출처는 {@link #canTransitionTo} 이며
 * 아래 다이어그램은 참고용.
 *
 * <pre>
 *  (없음) ──▶ AGENT_PENDING ──agent 등록──▶ ACTIVE ◄──heartbeat 회복──┐
 *         └─▶ UNKNOWN ──────health check──▶ ACTIVE ──heartbeat 끊김──┴─▶ INACTIVE
 * </pre>
 *
 * <p>운영 중 ACTIVE 와 INACTIVE 간 전이 빈번. agent 연결 상태를 그대로 반영.
 *
 * <p>{@code ClusterEntity.status} 는 String 컬럼. 값 설정 시 {@link #name()} 사용. 임의 문자열
 * 입력도 DB 가 차단하지 않음.
 */
public enum ClusterStatus {

    /** 초기 상태 또는 health 정보 부재. */
    UNKNOWN,

    /** Agent-first 등록: agent backfill 대기 중. */
    AGENT_PENDING,

    /** agent ACTIVE. */
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
        // 허용 범위를 넓게 잡는다. registered cluster 의 health 는 수시로 바뀐다.
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
