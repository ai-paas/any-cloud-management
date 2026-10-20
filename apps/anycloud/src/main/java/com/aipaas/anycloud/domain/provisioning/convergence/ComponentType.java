package com.aipaas.anycloud.domain.provisioning.convergence;

/**
 * VM 위에 설치되는 계층 중 desired state 대비 관측이 필요한 것.
 *
 * <p>AGENT 단일 항목인 이유. 나머지(GPU operator, ingress, monitoring 등)는 전부 addon 이며
 * addon 설치는 {@code HelmReleaseService} 가 agent gRPC 로 전달. agent 는 자기 자신을 설치할 수
 * 없어 백엔드 SSH 가 필요하므로 유일한 구성 요소.
 */
public enum ComponentType {
    AGENT
}
