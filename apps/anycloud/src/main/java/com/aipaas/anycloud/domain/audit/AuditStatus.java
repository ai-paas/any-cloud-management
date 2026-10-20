package com.aipaas.anycloud.domain.audit;

import com.aipaas.anycloud.common.error.enums.ErrorCode;
import com.aipaas.anycloud.common.error.exception.ErrorCodeAware;

/** 감사 기록에 남길 HTTP 상태. */
final class AuditStatus {

    private AuditStatus() {}

    /**
     * 예외가 자체 상태를 보유하면 해당 값 사용. 미분류 실패만 500.
     *
     * <p>전부 500 으로 기록하면 존재하지 않는 자원 조회 같은 일반적인 404 가 감사 로그에서
     * 장애로 집계.
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
