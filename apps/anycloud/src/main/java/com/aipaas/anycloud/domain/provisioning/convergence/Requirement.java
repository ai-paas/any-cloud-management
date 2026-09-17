package com.aipaas.anycloud.domain.provisioning.convergence;

/** 컴포넌트 미충족이 클러스터 READY 판정에 미치는 영향. */
public enum Requirement {
    /** 운영자가 명시적으로 요청한 사양. 미충족이면 READY 대신 DEGRADED. */
    REQUIRED,
    /** 없어도 클러스터는 유효. 상태로만 노출. */
    BEST_EFFORT,
    /** 이 클러스터에 해당 없음. probe 대상에서 제외. */
    NOT_APPLICABLE
}
