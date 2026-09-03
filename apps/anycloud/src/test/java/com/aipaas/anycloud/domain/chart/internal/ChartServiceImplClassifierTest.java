package com.aipaas.anycloud.domain.chart.internal;

import static org.assertj.core.api.Assertions.assertThat;

import com.aipaas.anycloud.common.error.enums.ErrorCode;
import com.aipaas.anycloud.common.error.exception.CustomException;
import io.aipaas.cluster.agent.runtime.HelmRoutingException;
import org.junit.jupiter.api.Test;

/**
 * {@link com.aipaas.anycloud.domain.chart.internal.HelmExceptionMapper#toClassifiedException} 회귀 — agent 의 error_code string 을 의미별
 * {@link ErrorCode} 로 정확히 분류하는지 확인. 운영자가 "agent 죽음 (503)" vs "정책에 막힘 (403)"
 * 즉시 구분할 수 있도록 핵심 매핑 lock.
 */
class ChartServiceImplClassifierTest {

    @Test
    void chartNotAllowed_mapsTo403() {
        HelmRoutingException e =
                new HelmRoutingException("Agent INSTALL_ADDON returned PERMISSION_DENIED (CHART_NOT_ALLOWED): "
                        + "chart chart-museum-external/ingress-nginx not in allowlist");

        CustomException result = HelmExceptionMapper.toClassifiedException(
                "install", "chart-museum-external/ingress-nginx on orb-001", e);

        assertThat(result.getErrorCode()).isEqualTo(ErrorCode.CHART_NOT_ALLOWED);
        assertThat(result.getErrorCode().getStatus()).isEqualTo(403);
        assertThat(result.getMessage()).contains("allowed_charts").contains("chart-museum-external/ingress-nginx");
    }

    @Test
    void namespaceNotAllowed_mapsTo403() {
        HelmRoutingException e =
                new HelmRoutingException("Agent INSTALL_ADDON returned PERMISSION_DENIED (NAMESPACE_NOT_ALLOWED): "
                        + "namespace 'restricted' not in allowed_namespaces");

        CustomException result = HelmExceptionMapper.toClassifiedException("install", "any-chart on orb-001", e);

        assertThat(result.getErrorCode()).isEqualTo(ErrorCode.AGENT_NAMESPACE_NOT_ALLOWED);
        assertThat(result.getErrorCode().getStatus()).isEqualTo(403);
        assertThat(result.getMessage()).contains("allowed_namespaces");
    }

    @Test
    void genericPermissionDenied_mapsTo403() {
        // CHART_NOT_ALLOWED / NAMESPACE_NOT_ALLOWED 매칭 안 되는 PERMISSION_DENIED — 일반 403.
        HelmRoutingException e = new HelmRoutingException(
                "Agent UNINSTALL_ADDON returned PERMISSION_DENIED (UNAUTHORIZED): " + "some custom policy denied");

        CustomException result = HelmExceptionMapper.toClassifiedException("uninstall", "release-x", e);

        assertThat(result.getErrorCode()).isEqualTo(ErrorCode.AGENT_PERMISSION_DENIED);
        assertThat(result.getErrorCode().getStatus()).isEqualTo(403);
    }

    @Test
    void noActiveSession_mapsTo503() {
        HelmRoutingException e = new HelmRoutingException("No active agent session: SessionClosedException");

        CustomException result = HelmExceptionMapper.toClassifiedException("install", "any-chart on orb-001", e);

        assertThat(result.getErrorCode()).isEqualTo(ErrorCode.AGENT_UNAVAILABLE);
        assertThat(result.getErrorCode().getStatus()).isEqualTo(503);
    }

    @Test
    void timeout_mapsTo503() {
        HelmRoutingException e = new HelmRoutingException("Agent INSTALL_ADDON timeout after 300s");

        CustomException result = HelmExceptionMapper.toClassifiedException("install", "slow-chart on orb-001", e);

        assertThat(result.getErrorCode()).isEqualTo(ErrorCode.AGENT_UNAVAILABLE);
    }

    @Test
    void chartNotAllowed_priorityOverGenericPermissionDenied() {
        // PERMISSION_DENIED 문자열도 포함된 CHART_NOT_ALLOWED 응답 — 더 구체적 mapping 우선해야.
        HelmRoutingException e =
                new HelmRoutingException("Agent INSTALL_ADDON returned PERMISSION_DENIED (CHART_NOT_ALLOWED): blah");

        CustomException result = HelmExceptionMapper.toClassifiedException("install", "any", e);

        assertThat(result.getErrorCode()).isEqualTo(ErrorCode.CHART_NOT_ALLOWED);
    }

    @Test
    void nullMessage_safelyHandled() {
        HelmRoutingException e = new HelmRoutingException(null);
        CustomException result = HelmExceptionMapper.toClassifiedException("install", "any", e);
        assertThat(result.getErrorCode()).isEqualTo(ErrorCode.AGENT_UNAVAILABLE);
    }

    // ============================================================================
    // HELM_INSTALL_FAILED 세분류 회귀
    // ============================================================================

    @Test
    void helmInstallFailed_protocolHandler_mapsTo400ChartResolution() {
        // 실제 production 에서 발견된 에러 (chart-museum-external/ingress-nginx, helm repo URL 누락).
        HelmRoutingException e = new HelmRoutingException("Agent INSTALL_ADDON returned FAILED (HELM_INSTALL_FAILED): "
                + "locate chart ingress-nginx: could not find protocol handler for: ");

        CustomException result = HelmExceptionMapper.toClassifiedException(
                "install", "chart-museum-external/ingress-nginx on orb-001", e);

        assertThat(result.getErrorCode()).isEqualTo(ErrorCode.HELM_CHART_RESOLUTION_FAILED);
        assertThat(result.getErrorCode().getStatus()).isEqualTo(400);
        assertThat(result.getMessage()).contains("helm repo").contains("repo URL");
    }

    @Test
    void helmInstallFailed_locateChart_mapsTo400ChartResolution() {
        HelmRoutingException e = new HelmRoutingException("Agent INSTALL_ADDON returned FAILED (HELM_INSTALL_FAILED): "
                + "failed to locate chart in repository: chart not found");

        CustomException result = HelmExceptionMapper.toClassifiedException("install", "missing/chart on orb-001", e);

        assertThat(result.getErrorCode()).isEqualTo(ErrorCode.HELM_CHART_RESOLUTION_FAILED);
    }

    @Test
    void helmInstallFailed_noSuchRepository_mapsTo400ChartResolution() {
        HelmRoutingException e = new HelmRoutingException(
                "Agent INSTALL_ADDON returned FAILED (HELM_INSTALL_FAILED): " + "no such repository: unknown-repo");

        CustomException result =
                HelmExceptionMapper.toClassifiedException("install", "unknown-repo/chart on orb-001", e);

        assertThat(result.getErrorCode()).isEqualTo(ErrorCode.HELM_CHART_RESOLUTION_FAILED);
    }

    @Test
    void helmInstallFailed_k8sApplyFail_mapsTo500HelmInstall() {
        // chart 해상은 됐지만 K8s apply 단계에서 실패 (RBAC / quota / 충돌 리소스).
        HelmRoutingException e = new HelmRoutingException("Agent INSTALL_ADDON returned FAILED (HELM_INSTALL_FAILED): "
                + "error validating data: ValidationError(Deployment.spec.template.spec.containers[0]): "
                + "unknown field \"imageTag\"");

        CustomException result = HelmExceptionMapper.toClassifiedException("install", "my-chart on orb-001", e);

        assertThat(result.getErrorCode()).isEqualTo(ErrorCode.HELM_INSTALL_FAILED);
        assertThat(result.getErrorCode().getStatus()).isEqualTo(500);
        assertThat(result.getMessage()).contains("K8s apply / hook 단계에서 실패");
    }

    @Test
    void helmInstallFailed_timeout_mapsTo500HelmInstall() {
        // timeout 도 K8s 단계 실패 — chart 해상 단어가 없으므로 HELM_INSTALL_FAILED (500).
        HelmRoutingException e = new HelmRoutingException(
                "Agent INSTALL_ADDON returned FAILED (HELM_INSTALL_FAILED): " + "timed out waiting for the condition");

        CustomException result = HelmExceptionMapper.toClassifiedException("install", "slow-chart on orb-001", e);

        assertThat(result.getErrorCode()).isEqualTo(ErrorCode.HELM_INSTALL_FAILED);
    }
}
