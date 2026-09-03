package com.aipaas.anycloud.domain.addon.installer;

import com.aipaas.anycloud.domain.addon.model.AddonType;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * {@link AddonType} → {@link AddonInstaller} bean lookup.
 *
 * <p>Spring DI 가 모든 AddonInstaller 구현체를 inject → constructor 가 type 별 indexing.
 * 동일 type 의 bean 이 2개 이상이면 fail-fast — duplicate strategy registration 차단.
 */
@Slf4j
@Component
public class AddonInstallerRegistry {

    private final Map<AddonType, AddonInstaller> byType;

    public AddonInstallerRegistry(List<AddonInstaller> installers) {
        Map<AddonType, AddonInstaller> map = new EnumMap<>(AddonType.class);
        for (AddonInstaller inst : installers) {
            AddonInstaller prior = map.put(inst.type(), inst);
            if (prior != null) {
                throw new IllegalStateException(String.format(
                        "Duplicate AddonInstaller for type %s: %s vs %s",
                        inst.type(), prior.getClass().getName(), inst.getClass().getName()));
            }
        }
        this.byType = Map.copyOf(map);
        log.info("AddonInstallerRegistry: {} installers registered — {}", byType.size(), byType.keySet());
    }

    /** type 에 해당하는 installer. 없으면 null — caller 가 GENERIC fallback 또는 에러 처리. */
    public AddonInstaller find(AddonType type) {
        return byType.get(type);
    }

    /** type lookup with fail-fast — missing 시 RuntimeException. */
    public AddonInstaller require(AddonType type) {
        AddonInstaller inst = byType.get(type);
        if (inst == null) {
            throw new IllegalStateException("No AddonInstaller registered for type=" + type);
        }
        return inst;
    }
}
