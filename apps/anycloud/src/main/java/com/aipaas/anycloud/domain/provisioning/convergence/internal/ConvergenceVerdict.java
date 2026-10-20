package com.aipaas.anycloud.domain.provisioning.convergence.internal;

/** REQUIRED 컴포넌트 관측 묶음에 대한 판정. */
enum ConvergenceVerdict {
    SATISFIED,
    UNSATISFIED,
    /** 관측 실패 혼재로 확정 불가. 상태 변경 없음. */
    INCONCLUSIVE
}
