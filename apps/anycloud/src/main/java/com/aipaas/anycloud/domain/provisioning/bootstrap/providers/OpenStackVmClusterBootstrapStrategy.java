package com.aipaas.anycloud.domain.provisioning.bootstrap.providers;

import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

@Component
@Order(20)
public class OpenStackVmClusterBootstrapStrategy extends PrivateLinuxVmClusterBootstrapStrategy {

    @Override
    public boolean supports(String provider) {
        return "OpenStack".equalsIgnoreCase(provider);
    }
}
