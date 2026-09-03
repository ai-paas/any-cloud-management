package com.aipaas.anycloud.domain.provisioning.convergence;

/** 관측 1회의 결과. API 응답과 조정 루프 판단의 입력. */
public record ComponentObservation(
        ComponentType type, Requirement requirement, ComponentHealth health, String detail) {}
