package com.aipaas.anycloud.common.error.exception.provisioning;

import com.aipaas.anycloud.common.error.enums.ErrorCode;

/**
 * 지금 상태로는 받을 수 없는 요청입니다. PROVISIONING 중인 cluster 에 다시 create, DELETED 에 scale
 * 같은 경우입니다. 409 로 나갑니다.
 *
 * <p>응답에 현재 상태를 실어 호출자가 언제 다시 시도할지 판단하게 합니다.
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
