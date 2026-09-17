package com.aipaas.anycloud.domain.provisioning.query;

import com.aipaas.anycloud.domain.provisioning.model.VmClusterStatus;

/**
 * 목록에서 삭제 이력을 감출지 결정한다.
 *
 * <p>삭제는 감사와 원인 분석을 위해 행을 남긴다. 기본 목록에 쌓이면 살아 있는 것을 찾기 어려워
 * 감추되, 함께 보고 싶을 때는 {@code includeDeleted} 로 연다. status 를 명시하면 그 필터가
 * 우선한다 — 토글이 뒤집으면 필터가 거짓말이 된다.
 */
public final class VmClusterListFilter {

    private VmClusterListFilter() {}

    public static boolean hidesDeleted(VmClusterStatus status, boolean includeDeleted) {
        return status == null && !includeDeleted;
    }
}
