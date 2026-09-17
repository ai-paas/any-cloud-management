package com.aipaas.anycloud.domain.audit;

import com.aipaas.anycloud.common.error.enums.ErrorCode;
import com.aipaas.anycloud.common.error.exception.ErrorCodeAware;

/** 감사 기록에 남길 HTTP 상태. */
final class AuditStatus {

    private AuditStatus() {}

    /**
     * 예외가 자신의 상태를 알면 그것을 쓴다. 모르는 실패만 500 이다.
     *
     * <p>전부 500 으로 남기면 없는 자원 조회 같은 평범한 404 가 감사 로그에서 장애로 읽힌다.
     */
    static int of(Throwable error) {
        if (error == null) {
            return 200;
        }
        if (error instanceof ErrorCodeAware aware) {
            ErrorCode code = aware.getErrorCode();
            if (code != null) {
                return code.getStatus();
            }
        }
        return 500;
    }
}
