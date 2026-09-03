package com.aipaas.anycloud.common.web;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

/**
 * 모든 list 응답의 표준 페이지 컨테이너. pagination 메타는 envelope 의 {@link ResponseMeta#pagination()}
 * 으로 전달되며, 본 record 는 items 만 보유.
 */
@Schema(description = "페이지 단위 list 응답 컨테이너")
public record PagedData<T>(@Schema(description = "이번 페이지의 항목들") List<T> items) {

    public static <T> PagedData<T> of(List<T> items) {
        return new PagedData<>(items == null ? List.of() : items);
    }
}
