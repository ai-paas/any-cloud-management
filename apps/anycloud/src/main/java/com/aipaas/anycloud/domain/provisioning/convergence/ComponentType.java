package com.aipaas.anycloud.domain.provisioning.convergence;

/** VM 위에 설치되는 계층 중 desired state 대비 관측이 필요한 것. */
public enum ComponentType {
    GPU_DRIVER,
    GPU_OPERATOR,
    INGRESS,
    AGENT
}
