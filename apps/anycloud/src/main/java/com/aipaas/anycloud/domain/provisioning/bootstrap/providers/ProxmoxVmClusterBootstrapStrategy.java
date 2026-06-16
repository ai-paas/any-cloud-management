package com.aipaas.anycloud.domain.provisioning.bootstrap.providers;

import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

@Component
@Order(80)
public class ProxmoxVmClusterBootstrapStrategy extends PrivateLinuxVmClusterBootstrapStrategy {

    @Override
    public boolean supports(String provider) {
        return "Proxmox".equalsIgnoreCase(provider);
    }
}
