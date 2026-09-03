package io.aipaas.cluster.provisioning.program.provisioner;

import io.aipaas.cluster.provisioning.program.ProviderName;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Canonical provider 토큰 → {@link ProviderProvisioner} 매핑. ProvisionerOrchestrator 이 spec.provider 로
 * lookup.
 *
 * <p>Spring 으로 wiring 가능 — host 가 모든 ProviderProvisioner bean 을 등록해서 본 registry 를
 * Spring bean 으로 주입. starter 는 default 구현 (NoopProviderProvisioner) 로 fallback.
 *
 * <p>thread-safety: 빌드 시 한 번 채워지고 read-only 사용. ConcurrentHashMap 불요.
 */
public final class ProviderRegistry {

    private final Map<String, ProviderProvisioner> provisioners;

    public ProviderRegistry(List<ProviderProvisioner> provisioners) {
        Map<String, ProviderProvisioner> map = new LinkedHashMap<>();
        for (ProviderProvisioner p : provisioners) {
            map.put(ProviderName.canonical(p.name()), p);
        }
        this.provisioners = Map.copyOf(map);
    }

    public ProviderProvisioner get(String provider) {
        String canonical = ProviderName.canonical(provider);
        ProviderProvisioner p = provisioners.get(canonical);
        if (p == null) {
            throw new IllegalStateException(
                    "No ProviderProvisioner registered for canonical provider '"
                            + canonical + "'. Registered: " + provisioners.keySet());
        }
        return p;
    }
}
