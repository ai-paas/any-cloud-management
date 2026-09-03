package com.aipaas.anycloud.common.error.exception.provisioning;

import com.aipaas.anycloud.common.error.enums.ErrorCode;

/**
 * 현재 리소스 상태가 요청과 충돌 — 예: 이미 PROVISIONING 중인 cluster 에 다시 create 시도,
 * DELETED state cluster 에 scale 시도, addon 이 FAILED 외 state 에서 retry 등.
 *
 * <p>HTTP 409 (STATE_CONFLICT). 기존엔 {@link IllegalStateException} → handler 가 409 로
 * 매핑했으나, 의미가 명확하지 않아 신규 코드는 본 예외 사용 권장.
 *
 * <p>호출자는 응답 본문의 hint / current state 를 보고 재시도 시점을 판단.
 */
public class StateConflictException extends ProvisioningException {

    public StateConflictException(String message) {
        super(message, ErrorCode.STATE_CONFLICT);
    }

    @Override
    public boolean isTransient() {
        // 상태가 변할 때까지는 동일 결과. caller 가 state 확인 후 재시도해야 의미 있음.
        return false;
    }
}
