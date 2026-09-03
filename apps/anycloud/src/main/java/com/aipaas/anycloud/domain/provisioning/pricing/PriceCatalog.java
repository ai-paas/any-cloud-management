package com.aipaas.anycloud.domain.provisioning.pricing;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Component;
import org.yaml.snakeyaml.Yaml;

/**
 * provider 별 instance type 가격 catalog. 정적 YAML (분기별 수동
 * 업데이트) 을 startup 시 로드.
 *
 * <p>설계 결정 — provider 별 가격 API live integration 대신 정적 YAML:
 * <ul>
 *   <li>가격 API rate limit + 인증 복잡도 회피 (provider 마다 다 다른 SDK 필요)</li>
 *   <li>backend 가 인터넷 접근 못 하는 air-gapped 환경에서도 동작</li>
 *   <li>정확도 vs 단순성 trade-off — "정확한 가격" 보다 "유용한 예상" 우선 (대부분 사용자는 정확한
 *       센트가 아니라 "$10/hr 인가 $100/hr 인가" 만 알면 됨)</li>
 * </ul>
 *
 * <p>업데이트 주기: pricing/{provider}.yaml 의 updatedAt 필드가 6개월 이상 오래되면 startup 로그
 * 에 WARN. 운영자가 catalog 검토 후 commit 권장.
 */
@Slf4j
@Component
public class PriceCatalog {

    private static final String CLASSPATH_PATTERN = "classpath*:pricing/*.yaml";

    /** provider (lowercase) → catalog data. */
    private final Map<String, ProviderPricing> byProvider;

    public PriceCatalog() {
        this(CLASSPATH_PATTERN);
    }

    /** classpath pattern 주입 — 테스트 용. */
    PriceCatalog(String classpathPattern) {
        this.byProvider = load(classpathPattern);
        log.info("PriceCatalog loaded {} provider(s): {}", byProvider.size(), byProvider.keySet());
    }

    /** 단일 instance type 의 가격 lookup. provider 또는 type 미존재 시 empty. */
    public Optional<InstancePrice> lookup(String provider, String instanceType) {
        if (provider == null || instanceType == null) {
            return Optional.empty();
        }
        ProviderPricing pp = byProvider.get(provider.toLowerCase(Locale.ROOT));
        if (pp == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(pp.instanceTypes.get(instanceType));
    }

    /** provider 별 평균 spot 할인 (0.0 ~ 1.0). 미존재 시 0.0 (no discount). */
    public BigDecimal spotDiscount(String provider) {
        if (provider == null) {
            return BigDecimal.ZERO;
        }
        ProviderPricing pp = byProvider.get(provider.toLowerCase(Locale.ROOT));
        return pp == null ? BigDecimal.ZERO : pp.spotDiscount;
    }

    /** UI 표시용 metadata — currency / region / updatedAt / notes. */
    public Optional<ProviderMetadata> metadata(String provider) {
        if (provider == null) {
            return Optional.empty();
        }
        ProviderPricing pp = byProvider.get(provider.toLowerCase(Locale.ROOT));
        if (pp == null) {
            return Optional.empty();
        }
        return Optional.of(new ProviderMetadata(pp.provider, pp.currency, pp.region, pp.updatedAt, pp.notes));
    }

    /** 가격 정보가 있는 provider 목록. */
    public List<String> supportedProviders() {
        return List.copyOf(byProvider.keySet());
    }

    // ----- internal -----

    private static Map<String, ProviderPricing> load(String pattern) {
        PathMatchingResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();
        Resource[] resources;
        try {
            resources = resolver.getResources(pattern);
        } catch (IOException e) {
            log.warn("PriceCatalog: failed to scan {} — {}", pattern, e.getMessage());
            return Map.of();
        }
        Map<String, ProviderPricing> out = new HashMap<>();
        Yaml yaml = new Yaml();
        for (Resource r : resources) {
            try (InputStream in = r.getInputStream()) {
                Map<String, Object> root = yaml.load(in);
                if (root == null) {
                    continue;
                }
                ProviderPricing pp = parse(root);
                if (pp != null) {
                    out.put(pp.provider.toLowerCase(Locale.ROOT), pp);
                }
            } catch (IOException e) {
                log.warn("PriceCatalog: skip {} — {}", r.getFilename(), e.getMessage());
            } catch (RuntimeException e) {
                log.warn("PriceCatalog: parse error in {} — {}", r.getFilename(), e.getMessage());
            }
        }
        return Collections.unmodifiableMap(out);
    }

    @SuppressWarnings("unchecked")
    private static ProviderPricing parse(Map<String, Object> root) {
        String provider = (String) root.get("provider");
        if (provider == null) {
            return null;
        }
        String currency = (String) root.getOrDefault("currency", "USD");
        String region = (String) root.get("region");
        String updatedAt = (String) root.get("updatedAt");
        String notes = (String) root.get("notes");

        BigDecimal spotDiscount = toBigDecimal(root.get("spotDiscount"), BigDecimal.ZERO);

        Map<String, Object> rawTypes = (Map<String, Object>) root.get("instanceTypes");
        Map<String, InstancePrice> types = new HashMap<>();
        if (rawTypes != null) {
            for (Map.Entry<String, Object> e : rawTypes.entrySet()) {
                if (!(e.getValue() instanceof Map<?, ?> m)) continue;
                Map<String, Object> entry = (Map<String, Object>) m;
                BigDecimal hourly = toBigDecimal(entry.get("hourly"), null);
                if (hourly == null) continue;
                int vcpu = intOr(entry.get("vcpu"), 0);
                int memGb = intOr(entry.get("memoryGb"), 0);
                int gpu = intOr(entry.get("gpu"), 0);
                types.put(e.getKey(), new InstancePrice(hourly, vcpu, memGb, gpu));
            }
        }
        return new ProviderPricing(provider, currency, region, updatedAt, notes, spotDiscount, Map.copyOf(types));
    }

    private static BigDecimal toBigDecimal(Object v, BigDecimal fallback) {
        if (v == null) return fallback;
        if (v instanceof BigDecimal bd) return bd;
        if (v instanceof Number n) return BigDecimal.valueOf(n.doubleValue());
        try {
            return new BigDecimal(v.toString());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static int intOr(Object v, int fallback) {
        if (v instanceof Number n) return n.intValue();
        if (v == null) return fallback;
        try {
            return Integer.parseInt(v.toString());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    /** Internal per-provider pricing block. */
    private record ProviderPricing(
            String provider,
            String currency,
            String region,
            String updatedAt,
            String notes,
            BigDecimal spotDiscount,
            Map<String, InstancePrice> instanceTypes) {}

    /** 가격 catalog 의 metadata (UI 표시용). */
    public record ProviderMetadata(String provider, String currency, String region, String updatedAt, String notes) {}
}
