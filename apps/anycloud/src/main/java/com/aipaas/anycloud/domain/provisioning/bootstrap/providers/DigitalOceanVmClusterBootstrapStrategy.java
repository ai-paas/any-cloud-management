package com.aipaas.anycloud.domain.provisioning.bootstrap.providers;

import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

@Component
@Order(70)
public class DigitalOceanVmClusterBootstrapStrategy extends GenericLinuxVmClusterBootstrapStrategy {

    @Override
    public boolean supports(String provider) {
        return "DigitalOcean".equalsIgnoreCase(provider);
    }
}
