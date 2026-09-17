package com.aipaas.anycloud.domain.provisioning.payload;

/**
 * 만들어진 노드가 기준이고, 아직 없으면 요청값으로 보여준다.
 *
 * <p>삼항으로 쓰면 {@code int} 와 {@code Integer} 가 섞여 Integer 가 언박싱된다. 스냅샷이 없는
 * 행에서 NPE 가 나 목록 전체가 500 이 됐다 — 삭제된 행은 스냅샷이 지워지므로 드문 경우가 아니다.
 */
public final class NodeCountFallback {

    private NodeCountFallback() {}

    public static Integer workerCount(boolean provisioned, int actual, Integer requested) {
        return provisioned ? Integer.valueOf(actual) : requested;
    }
}
