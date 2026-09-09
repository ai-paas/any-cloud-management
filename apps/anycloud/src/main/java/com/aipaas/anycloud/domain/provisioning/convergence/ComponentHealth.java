package com.aipaas.anycloud.domain.provisioning.convergence;

public enum ComponentHealth {
    READY,
    NOT_READY,
    /** probe 자체가 실패. 상태 전이를 일으키지 않는다. */
    UNKNOWN
}
