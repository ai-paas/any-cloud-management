package com.aipaas.anycloud.domain.provisioning.convergence;

/** 구성 요소 관측 1회의 결과. API 응답과 수렴 판정의 입력. */
public record ComponentObservation(ComponentType type, Requirement requirement, ComponentHealth health, String detail) {

    public ConvergenceSignal toSignal() {
        return new ConvergenceSignal(type.name(), requirement, health, detail);
    }
}
