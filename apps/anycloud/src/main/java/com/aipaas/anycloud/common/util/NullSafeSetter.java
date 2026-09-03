package com.aipaas.anycloud.common.util;

import java.util.function.Consumer;
import org.slf4j.Logger;

public final class NullSafeSetter {

    private NullSafeSetter() {}

    /**
     * 새 값이 null 이 아닐 때만 setter 호출 + debug log.
     *
     * <p>주로 PATCH 류 partial update 에서 — 클라이언트가 명시 안 한 필드를
     * 덮어쓰지 않기 위해 사용.
     */
    public static <T> void updateIfNotNull(
            T newValue, Consumer<T> setter, Logger logger, String logMessage, Object... args) {
        if (newValue != null) {
            setter.accept(newValue);
            logger.debug(logMessage, args);
        }
    }
}
