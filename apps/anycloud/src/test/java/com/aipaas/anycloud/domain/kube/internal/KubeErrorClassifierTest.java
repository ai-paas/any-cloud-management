package com.aipaas.anycloud.domain.kube.internal;

import static org.assertj.core.api.Assertions.assertThat;

import com.aipaas.anycloud.domain.kube.internal.KubeErrorClassifier.DegradedInfo;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import org.junit.jupiter.api.Test;

/**
 * {@link KubeErrorClassifier#classify} 회귀 — K8s 호출 cause chain 의 message 키워드를 표준 reason code 로
 * 분류하는 로직 검증.의 god class 분리 후 단위 테스트 가능해진 첫 회귀 lock.
 *
 * <p>의 cause unwrap 버그 (wrapper exception 의 "agent unavailable" prefix 가
 * root cause 의 진짜 키워드를 가리던 문제) 재발 방지 회귀.
 */
class KubeErrorClassifierTest {

    @Test
    void circuitBreakerOpen_mapsToCircuitOpen() {
        CircuitBreaker cb =
                CircuitBreakerRegistry.of(CircuitBreakerConfig.ofDefaults()).circuitBreaker("test");
        cb.transitionToOpenState();
        Throwable cause = CallNotPermittedException.createCallNotPermittedException(cb);

        DegradedInfo result = KubeErrorClassifier.classify(cause);

        assertThat(result.reason()).isEqualTo("CIRCUIT_OPEN");
        assertThat(result.message()).contains("Circuit breaker OPEN").contains("Retry in 30s");
    }

    @Test
    void namespaceNotAllowed_mapsToNamespaceNotAllowed() {
        Throwable t = new RuntimeException("NAMESPACE_NOT_ALLOWED: ns 'kube-system' not in allowlist");

        DegradedInfo result = KubeErrorClassifier.classify(t);

        assertThat(result.reason()).isEqualTo("NAMESPACE_NOT_ALLOWED");
        assertThat(result.message()).contains("allowlist");
    }

    @Test
    void resourceKindDenied_mapsToResourceKindDenied() {
        Throwable t = new RuntimeException("RESOURCE_KIND_DENIED: secrets policy denied");

        DegradedInfo result = KubeErrorClassifier.classify(t);

        assertThat(result.reason()).isEqualTo("RESOURCE_KIND_DENIED");
        assertThat(result.message()).contains("resource_policy");
    }

    @Test
    void unsupportedKind_mapsToUnsupportedKind() {
        Throwable t = new RuntimeException("UNSUPPORTED_KIND: cannot resolve frobnicators.example.com");

        DegradedInfo result = KubeErrorClassifier.classify(t);

        assertThat(result.reason()).isEqualTo("UNSUPPORTED_KIND");
        assertThat(result.message()).contains("CRD installation");
    }

    @Test
    void forbidden_mapsToForbidden() {
        Throwable t = new RuntimeException("FORBIDDEN: cannot list pods in namespace 'kube-system'");

        DegradedInfo result = KubeErrorClassifier.classify(t);

        assertThat(result.reason()).isEqualTo("FORBIDDEN");
        assertThat(result.message()).contains("RBAC").contains("ClusterRole");
    }

    @Test
    void permissionDenied_alsoMapsToForbidden() {
        Throwable t = new RuntimeException("PERMISSION_DENIED: agent SA cannot get configmaps");

        DegradedInfo result = KubeErrorClassifier.classify(t);

        assertThat(result.reason()).isEqualTo("FORBIDDEN");
    }

    @Test
    void noActiveSession_mapsToAgentInactive() {
        Throwable t = new RuntimeException("no active session for cluster orb-001");

        DegradedInfo result = KubeErrorClassifier.classify(t);

        assertThat(result.reason()).isEqualTo("AGENT_INACTIVE");
    }

    @Test
    void wrapperOnly_agentUnavailable_mapsToAgentInactive() {
        // cause 가 null 이고 wrapper-only message — INACTIVE 로 간주.
        Throwable t = new RuntimeException("Cluster agent unavailable");

        DegradedInfo result = KubeErrorClassifier.classify(t);

        assertThat(result.reason()).isEqualTo("AGENT_INACTIVE");
    }

    @Test
    void k2RegressionGuard_wrapperWithRootCause_classifiesFromRoot() {
        // 회귀 가드 — wrapper 의 "agent unavailable" prefix 가 root cause 의 "forbidden"
        // 키워드를 가리지 않도록 cause chain unwrap.
        Throwable root = new RuntimeException("FORBIDDEN: cannot list nodes");
        Throwable wrapper = new RuntimeException("Cluster agent unavailable: see cause", root);

        DegradedInfo result = KubeErrorClassifier.classify(wrapper);

        assertThat(result.reason())
                .as("root cause 의 FORBIDDEN 이 wrapper 의 'agent unavailable' 을 이겨야 함 (K-2 회귀)")
                .isEqualTo("FORBIDDEN");
    }

    @Test
    void unclassified_mapsToAgentErrorWithRootMessage() {
        Throwable t = new RuntimeException("unexpected RPC error: deadline exceeded");

        DegradedInfo result = KubeErrorClassifier.classify(t);

        assertThat(result.reason()).isEqualTo("AGENT_ERROR");
        assertThat(result.message()).contains("deadline exceeded");
    }

    @Test
    void nullThrowable_safelyDefaults() {
        DegradedInfo result = KubeErrorClassifier.classify(null);

        assertThat(result.reason()).isEqualTo("AGENT_ERROR");
        assertThat(result.message()).isEqualTo("Agent call failed");
    }

    @Test
    void priorityCheck_namespaceNotAllowed_winsOverForbidden() {
        // 두 키워드 모두 포함된 경우 — 더 구체적인 NAMESPACE_NOT_ALLOWED 가 이겨야 함.
        Throwable t = new RuntimeException("NAMESPACE_NOT_ALLOWED FORBIDDEN: ns denied");

        DegradedInfo result = KubeErrorClassifier.classify(t);

        assertThat(result.reason()).isEqualTo("NAMESPACE_NOT_ALLOWED");
    }

    // ============================================================================
    // normalizeKindLabel — Micrometer 카디널리티 폭증 방지
    // ============================================================================

    @Test
    void normalizeKindLabel_null_returnsUnknown() {
        assertThat(KubeErrorClassifier.normalizeKindLabel(null)).isEqualTo("unknown");
    }

    @Test
    void normalizeKindLabel_blank_returnsUnknown() {
        assertThat(KubeErrorClassifier.normalizeKindLabel("   ")).isEqualTo("unknown");
    }

    @Test
    void normalizeKindLabel_lowercases() {
        assertThat(KubeErrorClassifier.normalizeKindLabel("Pods")).isEqualTo("pods");
        assertThat(KubeErrorClassifier.normalizeKindLabel("ConfigMap")).isEqualTo("configmap");
    }

    @Test
    void normalizeKindLabel_trims() {
        assertThat(KubeErrorClassifier.normalizeKindLabel("  pods  ")).isEqualTo("pods");
    }

    @Test
    void normalizeKindLabel_preservesGvrFullForm() {
        // CRD 의 plural.group 형식 — Micrometer 라벨로 충분히 카디널리티 통제 가능.
        assertThat(KubeErrorClassifier.normalizeKindLabel("applications.argoproj.io"))
                .isEqualTo("applications.argoproj.io");
    }
}
