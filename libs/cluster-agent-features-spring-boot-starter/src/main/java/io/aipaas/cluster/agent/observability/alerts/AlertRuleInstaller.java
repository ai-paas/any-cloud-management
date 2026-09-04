package io.aipaas.cluster.agent.observability.alerts;

import com.google.protobuf.Struct;
import com.google.protobuf.Value;
import io.aipaas.cluster.agent.observability.core.ClusterCapabilities;
import io.aipaas.cluster.agent.observability.core.ObservabilityException;
import io.aipaas.cluster.agent.runtime.AgentSessionRegistry;
import io.aipaas.cluster.agent.v1.CommandRequest;
import io.aipaas.cluster.agent.v1.CommandResponse;
import io.aipaas.cluster.agent.v1.CommandType;
import io.aipaas.cluster.agent.v1.ControlMessage;
import io.aipaas.cluster.agent.v1.Status;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import lombok.extern.slf4j.Slf4j;

/**
 * anycloud-default PrometheusRule 카탈로그를 cluster 에 install/uninstall.
 *
 * <p>구현 전략: 자체 K8s client 없이, 기존 cluster-agent 의 {@link CommandType#APPLY_MANIFEST} 와
 * {@link CommandType#DELETE_RESOURCE} 를 reverse-tunnel 로 호출. allowlist / RBAC 은 agent 측이
 * 그대로 적용 — backend 가 K8s 자격을 가질 필요 없음.
 *
 * <p>placeholder 치환: catalog YAML 의 {@code ${NAMESPACE}} / {@code ${RELEASE}} 는 install
 * 시점에 호출 인자로 치환. 본 단순 토큰 치환으로 충분 — Spring SpEL 같은 무거운 evaluator 회피.
 *
 * <p>release label 의미: Prometheus Operator 가 PrometheusRule 을 discover 할 때 spec.ruleSelector
 * 와 매칭. kube-prometheus-stack 의 default ruleSelector 는 release=<release-name> 이므로 본 값과
 * 일치시켜야 rule 이 실제로 활성됨.
 */
@Slf4j
public class AlertRuleInstaller {

    private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(30);
    private static final String DEFAULT_NAMESPACE = "monitoring";
    private static final String DEFAULT_RELEASE = "kube-prometheus-stack";

    private final AgentSessionRegistry sessionRegistry;
    private final AlertRuleCatalog catalog;

    /** null 이면 capability 필터 없이 전체 설치 — SPI 미구현 호스트 호환. */
    private final ClusterCapabilities capabilities;

    public AlertRuleInstaller(AgentSessionRegistry sessionRegistry, AlertRuleCatalog catalog) {
        this(sessionRegistry, catalog, null);
    }

    public AlertRuleInstaller(
            AgentSessionRegistry sessionRegistry, AlertRuleCatalog catalog, ClusterCapabilities capabilities) {
        this.sessionRegistry = sessionRegistry;
        this.catalog = catalog;
        this.capabilities = capabilities;
    }

    /**
     * 단일 rule-set 설치. namespace/release 가 null/blank 면 default (monitoring / kube-prometheus-stack).
     *
     * @throws ObservabilityException ruleSetId 가 catalog 에 없으면 INVALID_PARAMS
     */
    public AlertRuleApplyResult install(
            String clusterName, String ruleSetId, String namespace, String release, Duration timeout) {
        AlertRuleSet rs = catalog.byId(ruleSetId)
                .orElseThrow(() -> new ObservabilityException("INVALID_PARAMS", "unknown rule-set id: " + ruleSetId));
        String ns = blankOr(namespace, DEFAULT_NAMESPACE);
        String rel = blankOr(release, DEFAULT_RELEASE);
        String manifest = substitute(rs.manifestYaml(), ns, rel);

        CommandResponse resp = dispatchApply(clusterName, manifest, ns, timeout);
        Map<String, Value> fields = resp.getResult().getFieldsMap();
        int count = (int) Math.round(
                fields.containsKey("applied_count")
                        ? fields.get("applied_count").getNumberValue()
                        : 0);
        return new AlertRuleApplyResult(clusterName, ruleSetId, ns, "anycloud-" + ruleSetId, count, "applied");
    }

    /**
     * 카탈로그 전체 설치. 일부 실패해도 나머지 시도 — 결과 list 에 status="applied"/"failed" 표시.
     * 응답에 cluster-wide 실패가 1건 이상이면 caller 가 partial 으로 판단.
     */
    public List<AlertRuleApplyResult> installAll(
            String clusterName, String namespace, String release, Duration timeout) {
        List<AlertRuleApplyResult> out = new ArrayList<>();
        for (AlertRuleSet rs : catalog.list()) {
            if (!supportedBy(clusterName, rs)) {
                log.debug(
                        "install-all: rule-set {} skipped on cluster {} \u2014 capability {} 없음",
                        rs.id(),
                        clusterName,
                        rs.requiredCapability());
                continue;
            }
            try {
                out.add(install(clusterName, rs.id(), namespace, release, timeout));
            } catch (ObservabilityException e) {
                log.warn("install-all: rule-set {} failed on cluster {} — {}", rs.id(), clusterName, e.getMessage());
                out.add(new AlertRuleApplyResult(
                        clusterName,
                        rs.id(),
                        blankOr(namespace, DEFAULT_NAMESPACE),
                        "anycloud-" + rs.id(),
                        0,
                        "failed: " + e.errorCode()));
            }
        }
        return out;
    }

    /**
     * capability 가 필요한 rule-set 을 그 능력이 없는 cluster 에 설치하지 않는다. 설치해도 지표가
     * 없어 절대 발화하지 않는 PrometheusRule 이 남고, 운영자가 알림 목록에서 혼동한다.
     *
     * <p>알 수 없는 capability 는 설치하는 쪽으로 둔다 — 새 라벨이 조용히 빠지는 것보다 낫다.
     */
    private boolean supportedBy(String clusterName, AlertRuleSet rs) {
        String required = rs.requiredCapability();
        if (required == null || capabilities == null) {
            return true;
        }
        if ("gpu".equals(required)) {
            return capabilities.hasGpuNodes(clusterName);
        }
        return true;
    }

    /** 단일 rule-set 제거 — {@code PrometheusRule/anycloud-<id>} 삭제. */
    public AlertRuleApplyResult uninstall(String clusterName, String ruleSetId, String namespace, Duration timeout) {
        String ns = blankOr(namespace, DEFAULT_NAMESPACE);
        String name = "anycloud-" + ruleSetId;
        dispatchDelete(clusterName, "PrometheusRule", ns, name, timeout);
        return new AlertRuleApplyResult(clusterName, ruleSetId, ns, name, 0, "deleted");
    }

    // ----- dispatch -----

    private CommandResponse dispatchApply(String clusterName, String manifest, String namespace, Duration timeout) {
        Struct params = Struct.newBuilder()
                .putFields("manifest", strVal(manifest))
                .putFields("namespace", strVal(namespace))
                .putFields("force", strVal("true"))
                .build();
        return send(clusterName, CommandType.APPLY_MANIFEST, params, timeout);
    }

    private void dispatchDelete(String clusterName, String kind, String namespace, String name, Duration timeout) {
        Struct params = Struct.newBuilder()
                .putFields("kind", strVal(kind))
                .putFields("namespace", strVal(namespace))
                .putFields("name", strVal(name))
                .build();
        send(clusterName, CommandType.DELETE_RESOURCE, params, timeout);
    }

    private CommandResponse send(String clusterName, CommandType type, Struct params, Duration timeout) {
        Duration effective = timeout == null ? DEFAULT_TIMEOUT : timeout;
        ControlMessage.Builder builder = ControlMessage.newBuilder()
                .setCommand(CommandRequest.newBuilder()
                        .setType(type)
                        .setParams(params)
                        .setTimeoutSeconds((int) effective.getSeconds())
                        .build());
        try {
            CommandResponse resp = sessionRegistry
                    .sendCommand(clusterName, builder, (int) effective.getSeconds())
                    .get(effective.toMillis() + 2000, TimeUnit.MILLISECONDS);
            if (resp.getStatus() != Status.OK) {
                throw new ObservabilityException(
                        resp.getErrorCode().isEmpty() ? "AGENT_ERROR" : resp.getErrorCode(), resp.getErrorMessage());
            }
            return resp;
        } catch (AgentSessionRegistry.NoActiveSessionException e) {
            throw new ObservabilityException("NO_ACTIVE_AGENT", "no active agent stream for cluster " + clusterName, e);
        } catch (TimeoutException e) {
            throw new ObservabilityException(
                    "TIMEOUT", "timeout waiting for agent response (cluster=" + clusterName + ")", e);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof AgentSessionRegistry.NoActiveSessionException) {
                throw new ObservabilityException(
                        "NO_ACTIVE_AGENT", "no active agent stream for cluster " + clusterName, cause);
            }
            if (cause instanceof AgentSessionRegistry.SessionClosedException) {
                throw new ObservabilityException(
                        "NO_ACTIVE_AGENT", "agent stream closed mid-request (cluster=" + clusterName + ")", cause);
            }
            throw new ObservabilityException("AGENT_CALL_FAILED", cause == null ? e.toString() : cause.toString(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ObservabilityException("INTERRUPTED", "interrupted", e);
        }
    }

    // ----- helpers -----

    /** "${NAMESPACE}" / "${RELEASE}" 토큰 단순 치환. */
    static String substitute(String yaml, String namespace, String release) {
        return yaml.replace("${NAMESPACE}", namespace == null ? "" : namespace)
                .replace("${RELEASE}", release == null ? "" : release);
    }

    private static String blankOr(String s, String fallback) {
        return (s == null || s.isBlank()) ? fallback : s;
    }

    private static Value strVal(String s) {
        return Value.newBuilder().setStringValue(s == null ? "" : s).build();
    }
}
