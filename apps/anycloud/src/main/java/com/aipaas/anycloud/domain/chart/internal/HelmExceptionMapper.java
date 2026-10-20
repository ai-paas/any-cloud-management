package com.aipaas.anycloud.domain.chart.internal;

import com.aipaas.anycloud.common.error.enums.ErrorCode;
import com.aipaas.anycloud.common.error.exception.CustomException;
import io.aipaas.cluster.agent.runtime.HelmRoutingException;
import java.util.Locale;

/**
 * agent 의 helm 실패를 {@link ErrorCode} 로 분류해 {@link CustomException} 으로 바꿉니다.
 *
 * <p>{@code HELM_INSTALL_FAILED} 하나가 두 갈래로 갈립니다. {@code locate chart},
 * {@code protocol handler}, {@code no such repository} 가 보이면 chart 를 못 찾은 것이라 호출자가
 * 고칠 수 있는 400 이고, 그 밖에는 K8s apply 나 hook 이 실패한 500 입니다. 둘을 섞으면 운영자가
 * 자기 입력을 의심해야 할지 cluster 를 봐야 할지 알 수 없습니다.
 */
public final class HelmExceptionMapper {

    private HelmExceptionMapper() {}

    /**
     * agent error message 를 분석해 적절한 {@link ErrorCode} 의 {@link CustomException} 으로 변환.
     *
     * @param operation 사용자가 시도한 helm op (예: "install", "upgrade", "rollback")
     * @param context   error message 에 동봉할 추가 컨텍스트 (예: "release=foo, chart=bar/baz")
     * @param e         agent 가 throw 한 routing exception
     * @return 분류된 CustomException: caller 가 throw
     */
    public static CustomException toClassifiedException(String operation, String context, HelmRoutingException e) {
        String msg = e.getMessage() == null ? "" : e.getMessage();
        String upper = msg.toUpperCase(Locale.ROOT);

        // 우선순위: 가장 구체적 → 일반화. CHART_NOT_ALLOWED 가 PERMISSION_DENIED 안에 포함되므로 먼저 체크.
        if (upper.contains("CHART_NOT_ALLOWED")) {
            return new CustomException(
                    "Agent allowlist 에 등록되지 않은 chart 입니다 — " + context + ". "
                            + "ConfigMap aipaas-agent-allowlist 의 allowed_charts 에 해당 chart 를 등록 후 재시도. "
                            + "원본 에러: " + msg,
                    ErrorCode.CHART_NOT_ALLOWED);
        }
        if (upper.contains("NAMESPACE_NOT_ALLOWED")) {
            return new CustomException(
                    "Agent allowlist 에 등록되지 않은 namespace 입니다 — " + context + ". "
                            + "ConfigMap aipaas-agent-allowlist 의 allowed_namespaces 에 추가 후 재시도. "
                            + "원본 에러: " + msg,
                    ErrorCode.AGENT_NAMESPACE_NOT_ALLOWED);
        }
        if (upper.contains("PERMISSION_DENIED")) {
            return new CustomException(
                    "Agent 정책이 " + operation + " 요청을 거부했습니다 — " + context + ". "
                            + "agent ConfigMap 의 allowlist / resource_policy 확인. 원본 에러: " + msg,
                    ErrorCode.AGENT_PERMISSION_DENIED);
        }

        // HELM_INSTALL_FAILED: agent 정상 / helm SDK 실패. chart 해상 (locate/repo/protocol)
        // 단어가 보이면 caller-fixable 400. 그 외 K8s apply / hook 실패는 500.
        if (upper.contains("HELM_INSTALL_FAILED")) {
            if (upper.contains("LOCATE CHART")
                    || upper.contains("PROTOCOL HANDLER")
                    || upper.contains("NO SUCH REPOSITORY")
                    || upper.contains("REPO NOT FOUND")
                    || upper.contains("NOT FOUND IN REPOSITORY")) {
                return new CustomException(
                        "Agent 가 chart 를 찾지 못했습니다 — " + context + ". "
                                + "원인: (1) helm repo 가 agent 에 등록 안 됨 (allowlist 에 있어도 별개), "
                                + "(2) repo URL 누락 / 오타, (3) chart 이름 / 버전 typo. "
                                + "agent 측 helm repo 설정 + values 의 chart 경로 검증 후 재시도. "
                                + "원본 에러: " + msg,
                        ErrorCode.HELM_CHART_RESOLUTION_FAILED);
            }
            // K8s apply / hook / timeout 실패: agent 는 정상, install 의 K8s side 가 실패.
            return new CustomException(
                    "Helm install 이 K8s apply / hook 단계에서 실패 — " + context + ". "
                            + "values 검증 + 대상 namespace 의 RBAC / quota / 충돌 리소스 확인. "
                            + "원본 에러: " + msg,
                    ErrorCode.HELM_INSTALL_FAILED);
        }

        // agent 비활성 / 통신 실패 → 503 그대로.
        return new CustomException(
                "Cluster agent failed to " + operation + " " + context + ": " + msg, ErrorCode.AGENT_UNAVAILABLE);
    }
}
