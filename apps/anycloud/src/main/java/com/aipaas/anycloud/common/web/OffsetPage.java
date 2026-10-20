package com.aipaas.anycloud.common.web;

import java.util.List;

/**
 * 단일 페이지와 전체 개수.
 *
 * <p>중간 계층이 개별적으로 자르면 계층별 페이지 기준이 분리. 절단 지점을 한 곳으로 통합.
 */
public record OffsetPage<T>(List<T> items, long total) {

    /** 0-based 페이지. 범위 초과 시 빈 페이지. 마지막 페이지에서 항목을 삭제한 경우 해당. */
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
