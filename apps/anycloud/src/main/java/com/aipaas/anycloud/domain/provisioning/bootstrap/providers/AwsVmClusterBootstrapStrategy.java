package com.aipaas.anycloud.domain.provisioning.bootstrap.providers;

import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

@Component
@Order(10)
public class AwsVmClusterBootstrapStrategy extends GenericLinuxVmClusterBootstrapStrategy {

    @Override
    public boolean supports(String provider) {
        return "AWS".equalsIgnoreCase(provider);
    }
}
