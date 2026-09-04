package io.aipaas.cluster.agent.observability.alerts;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.util.StreamUtils;

/**
 * Classpath 의 {@code alert-rules/*.yaml} 을 로드해 id 별로 보관.
 *
 * <p>본 catalog 는 starter 시작 시 한 번 로드되어 immutable. id 는 파일명 (확장자 제외). 호스트
 * 애플리케이션이 별도 rule set 을 추가하려면 본 catalog 를 override 하거나 자체 bean 으로 합성.
 *
 * <p>displayName / description / ruleCount 는 manifest YAML 의 metadata.name / annotations /
 * spec.groups[].rules[] 길이로부터 추정 — 별도 metadata 파일 불필요.
 */
@Slf4j
public class AlertRuleCatalog {

    private static final String CLASSPATH_PATTERN = "classpath*:alert-rules/*.yaml";

    private final Map<String, AlertRuleSet> byId;

    public AlertRuleCatalog() {
        this(CLASSPATH_PATTERN);
    }

    /** 패턴 주입 — 테스트 용도 (별도 디렉토리에서 로드). */
    AlertRuleCatalog(String classpathPattern) {
        this.byId = load(classpathPattern);
        log.info("AlertRuleCatalog loaded {} rule-set(s): {}", byId.size(), byId.keySet());
    }

    /** 전체 catalog. 순서: ID alphabetical. */
    public List<AlertRuleSet> list() {
        return List.copyOf(byId.values());
    }

    /** id 로 단일 lookup. */
    public Optional<AlertRuleSet> byId(String id) {
        return Optional.ofNullable(byId.get(id));
    }

    /** 본 catalog 가 알고 있는 모든 id (UI 가 install-all 호출 전 미리 보여줄 때). */
    public List<String> ids() {
        return List.copyOf(byId.keySet());
    }

    // ----- internal -----

    /** {@code metadata.labels.requires-capability} 값. 정규식으로 충분하다 — 라벨 한 줄이다. */
    private static String requiredCapability(String yaml) {
        java.util.regex.Matcher m = REQUIRES_CAPABILITY.matcher(yaml);
        return m.find() ? m.group(1) : null;
    }

    private static final java.util.regex.Pattern REQUIRES_CAPABILITY =
            java.util.regex.Pattern.compile("(?m)^\\s*requires-capability:\\s*(\\S+)\\s*$");

    private static Map<String, AlertRuleSet> load(String pattern) {
        PathMatchingResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();
        Resource[] resources;
        try {
            resources = resolver.getResources(pattern);
        } catch (IOException e) {
            throw new UncheckedIOException("failed to scan " + pattern, e);
        }
        Map<String, AlertRuleSet> out = new LinkedHashMap<>();
        // 정렬 안정성 — id alphabetical 로 UI 표시.
        java.util.Arrays.sort(resources, java.util.Comparator.comparing(Resource::getFilename));
        for (Resource r : resources) {
            String filename = r.getFilename();
            if (filename == null || !filename.endsWith(".yaml")) {
                continue;
            }
            String id = filename.substring(0, filename.length() - ".yaml".length());
            String yaml;
            try {
                yaml = StreamUtils.copyToString(r.getInputStream(), StandardCharsets.UTF_8);
            } catch (IOException e) {
                log.warn("alert-rule load skipped: {} — {}", filename, e.getMessage());
                continue;
            }
            int ruleCount = countRules(yaml);
            out.put(
                    id,
                    new AlertRuleSet(id, toDisplayName(id), describe(id), ruleCount, requiredCapability(yaml), yaml));
        }
        return Collections.unmodifiableMap(out);
    }

    /** YAML 의 {@code - alert:} prefix 개수로 alert rule 개수 추정 — full parser 회피. */
    private static int countRules(String yaml) {
        int count = 0;
        for (String line : yaml.split("\n")) {
            String t = line.trim();
            if (t.startsWith("- alert:")) {
                count++;
            }
        }
        return count;
    }

    private static String toDisplayName(String id) {
        return switch (id) {
            case "node" -> "노드 하드웨어 (CPU/메모리/디스크/네트워크)";
            case "kube-node" -> "Kubernetes 노드 상태";
            case "pod" -> "Pod lifecycle";
            case "controllers" -> "워크로드 컨트롤러 (Deployment/StatefulSet/Job/HPA)";
            case "storage" -> "PersistentVolume / PVC";
            case "control-plane" -> "Kubernetes 컨트롤 플레인";
            case "prometheus-stack" -> "Observability 스택 자체 (Prometheus/Alertmanager)";
            default -> id;
        };
    }

    private static String describe(String id) {
        return switch (id) {
            case "node" -> "node_exporter 기반 — 고 CPU, 메모리, 디스크, 네트워크 errs, NodeDown 5종.";
            case "kube-node" -> "kube-state-metrics — NodeNotReady + MemoryPressure/DiskPressure/PIDPressure.";
            case "pod" -> "PodCrashLooping + PodPendingTooLong + PodNotReady (Job 소유자 제외).";
            case "controllers" -> "Deployment rollout stuck, StatefulSet replicas mismatch, Job failed, HPA scaling unable.";
            case "storage" -> "PV 잔여 공간 < 15% + PV phase Failed/Pending.";
            case "control-plane" -> "kube-apiserver up==0 + p99 latency > 1s.";
            case "prometheus-stack" -> "Prometheus 스크랩 실패 / 룰 실패 / 0 ingestion + Alertmanager reload/notification 실패.";
            default -> "anycloud-default rule set.";
        };
    }
}
