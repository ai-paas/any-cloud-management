package com.aipaas.anycloud.domain.provisioning.convergence;

/**
 * VM 위에 설치되는 계층 중 desired state 대비 관측이 필요한 것.
 *
 * <p>GPU 드라이버는 항목이 아니다 — GPU operator 가 driver.enabled=true 로 컨테이너 드라이버를
 * 관리한다. 호스트에 드라이버를 따로 깔면 operator 의 driver 파드가 종료되고, NVIDIA 가 금지하는
 * 조합이 된다.
 */
public enum ComponentType {
    GPU_OPERATOR,
    INGRESS,
    AGENT
}
