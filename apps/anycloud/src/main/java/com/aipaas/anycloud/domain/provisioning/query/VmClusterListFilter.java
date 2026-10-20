package com.aipaas.anycloud.domain.provisioning.query;

import com.aipaas.anycloud.domain.provisioning.model.VmClusterStatus;

/**
 * 목록의 삭제 이력 표시 여부 결정.
 *
 * <p>삭제 시 감사와 원인 분석용 행을 보존. 기본 목록에 누적되면 유효 항목 식별이 어려워 숨김
 * 처리하고, 함께 조회할 때는 {@code includeDeleted} 사용. status 명시 시 해당 필터 우선.
 * 토글이 필터를 덮으면 필터 결과가 요청과 불일치.
 */
public final class VmClusterListFilter {

    private VmClusterListFilter() {}

    public static boolean hidesDeleted(VmClusterStatus status, boolean includeDeleted) {
        return status == null && !includeDeleted;
    }
}
