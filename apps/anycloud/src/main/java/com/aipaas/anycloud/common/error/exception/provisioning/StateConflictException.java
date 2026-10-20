package com.aipaas.anycloud.common.error.exception.provisioning;

import com.aipaas.anycloud.common.error.enums.ErrorCode;

/**
 * 현재 상태에서 수용 불가한 요청. PROVISIONING 중인 cluster 의 재생성, DELETED 상태의 scale 등.
 * 409 응답.
 *
 * <p>응답에 현재 상태를 포함해 호출자의 재시도 시점 판단에 사용.
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
