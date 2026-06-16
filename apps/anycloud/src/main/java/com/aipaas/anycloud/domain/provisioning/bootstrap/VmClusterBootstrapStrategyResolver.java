package com.aipaas.anycloud.domain.provisioning.bootstrap;

import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class VmClusterBootstrapStrategyResolver {

    private final List<VmClusterBootstrapStrategy> strategies;

    public VmClusterBootstrapStrategy resolve(String provider) {
        return strategies.stream()
                .filter(strategy -> strategy.supports(provider))
                .findFirst()
                .orElseThrow(
                        () -> new IllegalStateException("No VM cluster bootstrap strategy for provider " + provider));
    }
}
