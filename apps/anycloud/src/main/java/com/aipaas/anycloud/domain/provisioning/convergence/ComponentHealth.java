package com.aipaas.anycloud.domain.provisioning.convergence;

public enum ComponentHealth {
    READY,
    NOT_READY,
    /** probe 자체 실패. 상태 전이 미발생. */
    UNKNOWN
}
