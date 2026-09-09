package com.aipaas.anycloud.domain.provisioning.convergence.internal;

/** REQUIRED 컴포넌트 관측 묶음에 대한 판정. */
enum ConvergenceVerdict {
    SATISFIED,
    UNSATISFIED,
    /** 관측 실패가 섞여 확정할 수 없음. 상태를 바꾸지 않는다. */
    INCONCLUSIVE
}
