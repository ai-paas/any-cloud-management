package com.aipaas.anycloud.domain.provisioning.bootstrap.providers;

import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

@Component
@Order(40)
public class AzureVmClusterBootstrapStrategy extends GenericLinuxVmClusterBootstrapStrategy {

    @Override
    public boolean supports(String provider) {
        return "Azure".equalsIgnoreCase(provider);
    }
}
