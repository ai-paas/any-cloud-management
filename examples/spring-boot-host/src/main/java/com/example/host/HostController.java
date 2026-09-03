package com.example.host;

import io.aipaas.cluster.agent.runtime.AgentSessionRegistry;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 3 starter 의 AutoConfiguration 이 정상적으로 wiring 되었는지 확인하는 minimal endpoint.
 *
 * <p>각 starter 의 핵심 bean 1개씩을 optional inject 합니다. {@link AgentSessionRegistry} 는
 * cluster-agent-starter 의 core bean 이며, cluster-agent-features 의 sub-feature (rbac/backup/
 * observability) 와 cluster-provisioning 의 bean 은 host SPI 미구현 시 등록되지 않을 수 있으므로
 * {@link ObjectProvider} 로 안전하게 조회합니다.
 *
 * <p>실행 후 다음 endpoint 로 검증합니다.
 * <pre>{@code
 * curl http://localhost:8080/info
 * }</pre>
 */
@RestController
public class HostController {

    private final AgentSessionRegistry sessionRegistry;
    private final ObjectProvider<Object> featuresProbe;
    private final ObjectProvider<Object> provisioningProbe;

    public HostController(
            AgentSessionRegistry sessionRegistry,
            ObjectProvider<Object> featuresProbe,
            ObjectProvider<Object> provisioningProbe) {
        this.sessionRegistry = sessionRegistry;
        this.featuresProbe = featuresProbe;
        this.provisioningProbe = provisioningProbe;
    }

    @GetMapping("/info")
    public Map<String, Object> info() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("cluster-agent-starter", sessionRegistry != null ? "wired" : "missing");
        result.put(
                "cluster-agent-features-starter",
                featuresProbe.getIfAvailable() != null
                        ? "wired"
                        : "host SPI 미제공 (rbac/backup/observability 모두 비활성)");
        result.put(
                "cluster-provisioning-starter",
                provisioningProbe.getIfAvailable() != null ? "wired" : "host SPI 미제공");
        return result;
    }
}
