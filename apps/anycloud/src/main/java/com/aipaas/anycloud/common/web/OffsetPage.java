package com.aipaas.anycloud.common.web;

import java.util.List;

/**
 * 한 페이지와 전체 개수.
 *
 * <p>목록을 전량 내려보내면 노드가 수백인 환경에서 응답이 그만큼 커지고, 중간 계층이 잘라 내면
 * 각 층이 서로 다른 페이지를 가정하게 된다. 자르는 곳을 한 곳으로 둔다.
 */
public record OffsetPage<T>(List<T> items, long total) {

    /** 0-based 페이지. 범위를 벗어나면 빈 페이지다 — 마지막 페이지에서 지우면 그렇게 된다. */
    public static <T> OffsetPage<T> of(List<T> all, Integer page, Integer size) {
        if (all == null || all.isEmpty()) {
            return new OffsetPage<>(List.of(), 0);
        }
        if (size == null || size <= 0) {
            return new OffsetPage<>(all, all.size());
        }
        int from = Math.max(0, (page == null ? 0 : page) * size);
        if (from >= all.size()) {
            return new OffsetPage<>(List.of(), all.size());
        }
        return new OffsetPage<>(all.subList(from, Math.min(from + size, all.size())), all.size());
    }
}
