package com.aipaas.anycloud.domain.provisioning.convergence;

/**
 * VM 위에 설치되는 계층 중 desired state 대비 관측이 필요한 것.
 *
 * <p>AGENT 하나뿐인 이유 — 나머지(GPU operator, ingress, monitoring 등)는 전부 addon 이고,
 * addon 설치는 {@code HelmReleaseService} 가 agent gRPC 로 보낸다. agent 만 자기 자신을 설치할 수
 * 없어 백엔드 SSH 가 필요하고, 그래서 유일하게 구성 요소로 남는다.
 */
public enum ComponentType {
    AGENT
}
