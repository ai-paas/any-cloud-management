package com.aipaas.anycloud.common.error;

import static org.assertj.core.api.Assertions.assertThat;

import com.aipaas.anycloud.common.error.enums.ErrorCode;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

/**
 * ErrorCode 의 이름과 HTTP status 가 어긋나지 않는지 확인.
 *
 * <p>ENTITY_NOT_FOUND 가 400 이던 시절, 전용 예외 핸들러는 404 를 하드코딩하고 body 는
 * enum 의 400 을 실어 HTTP 404 + body 400 이 나갔다. CustomException 경로는 400 을 그대로 내
 * 같은 리소스 부재가 메서드에 따라 다른 상태를 반환했다.
 */
class ErrorCodeStatusConsistencyTest {

    /** 이름이 부재를 뜻하면 404 여야 한다. */
    @Test
    @DisplayName("NOT_FOUND 로 끝나는 ErrorCode 는 404 를 쓴다")
    void notFoundCodes_use404() {
        List<ErrorCode> mismatched = Arrays.stream(ErrorCode.values())
                .filter(c -> c.name().endsWith("NOT_FOUND"))
                .filter(c -> c.getStatus() != HttpStatus.NOT_FOUND.value())
                .toList();

        assertThat(mismatched)
                .as("이름은 NOT_FOUND 인데 status 가 404 가 아니다 — %s", mismatched)
                .isEmpty();
    }

    @Test
    @DisplayName("ENTITY_NOT_FOUND 는 404")
    void entityNotFound_is404() {
        assertThat(ErrorCode.ENTITY_NOT_FOUND.getStatus()).isEqualTo(HttpStatus.NOT_FOUND.value());
    }

    @Test
    @DisplayName("선언된 status 는 전부 유효한 HTTP 코드여야 한다")
    void allStatuses_areValidHttpCodes() {
        for (ErrorCode c : ErrorCode.values()) {
            assertThat(HttpStatus.resolve(c.getStatus()))
                    .as("%s 의 status=%d 는 유효한 HTTP 코드가 아니다", c.name(), c.getStatus())
                    .isNotNull();
        }
    }
}
