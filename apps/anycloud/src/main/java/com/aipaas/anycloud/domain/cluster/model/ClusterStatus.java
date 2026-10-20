package com.aipaas.anycloud.domain.cluster.model;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * 등록된 cluster 의 lifecycle status 입니다. 전이 규칙은 {@link #canTransitionTo} 가 단일 출처이고,
 * 아래 그림은 읽기용입니다.
 *
 * <pre>
 *  (없음) ──▶ AGENT_PENDING ──agent 등록──▶ ACTIVE ◄──heartbeat 회복──┐
 *         └─▶ UNKNOWN ──────health check──▶ ACTIVE ──heartbeat 끊김──┴─▶ INACTIVE
 * </pre>
 *
 * <p>운영 중 ACTIVE 와 INACTIVE 는 자주 오갑니다. agent 연결 상태를 그대로 반영하기 때문입니다.
 *
 * <p>{@code ClusterEntity.status} 는 String 컬럼입니다. 값을 넣을 때 {@link #name()} 을 쓰세요.
 * 임의 문자열이 들어가도 DB 가 막지 않습니다.
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
