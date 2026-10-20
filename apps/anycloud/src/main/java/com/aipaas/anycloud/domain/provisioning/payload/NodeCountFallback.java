package com.aipaas.anycloud.domain.provisioning.payload;

/**
 * 생성된 노드가 기준이며 미생성 상태면 요청값 사용.
 *
 * <p>삼항 연산자 사용 시 {@code int} 와 {@code Integer} 가 혼재해 Integer 가 언박싱. 스냅샷이
 * 없는 행에서 NPE 가 발생해 목록 전체가 500. 삭제된 행은 스냅샷이 제거되므로 빈번한 경우.
 */
public final class NodeCountFallback {

    private NodeCountFallback() {}

    public static Integer workerCount(boolean provisioned, int actual, Integer requested) {
        return provisioned ? Integer.valueOf(actual) : requested;
    }
}
