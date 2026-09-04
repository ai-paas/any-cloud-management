package com.aipaas.anycloud.domain.provisioning.convergence;

/**
 * 수렴 판정의 입력 단위. 구성 요소 probe 와 요청 addon 의 설치 상태가 같은 형태로 들어온다.
 *
 * @param source 운영자에게 보여줄 대상 이름 (구성 요소 타입 또는 addon catalogId)
 */
public record ConvergenceSignal(String source, Requirement requirement, ComponentHealth health, String detail) {}
