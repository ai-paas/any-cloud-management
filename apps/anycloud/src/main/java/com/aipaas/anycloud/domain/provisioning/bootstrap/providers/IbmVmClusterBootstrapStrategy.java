package com.aipaas.anycloud.domain.provisioning.bootstrap.providers;

import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

@Component
@Order(80)
public class IbmVmClusterBootstrapStrategy extends GenericLinuxVmClusterBootstrapStrategy {

    @Override
    public boolean supports(String provider) {
        return "IBM".equalsIgnoreCase(provider);
    }

    /** IBM VPC 는 IP-in-IP 미전달. 상세 사유는 상위 메서드 주석 참조. */
    @Override
    protected boolean usesVxlanEncapsulation() {
        return true;
    }
}
