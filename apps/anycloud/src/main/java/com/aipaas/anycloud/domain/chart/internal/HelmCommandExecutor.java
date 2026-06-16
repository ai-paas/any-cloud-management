package com.aipaas.anycloud.domain.chart.internal;

import com.aipaas.anycloud.common.error.exception.HelmDeploymentException;
import com.aipaas.anycloud.common.util.CommandExecutionSupport;
import com.aipaas.anycloud.common.util.CommandExecutionSupport.CommandExecutionResult;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Helm CLI subprocess executor — <b>오직 {@code helm template}</b> 만 지원.
 *
 * <p>유효 use case 는 {@link com.aipaas.anycloud.domain.agent.bootstrap.AgentChartRenderer} 가
 * cluster-agent 자체를 install 할 때 chart 를 로컬 렌더링해 manifest YAML 만 만든 다음 fabric8 의
 * server-side apply 로 적용. cluster bootstrap (agent 가 아직 없을 때) 이라 in-process
 * helm SDK 가 없으니 CLI 호출이 합리적. 모든 cluster-bound helm op 는 cluster-agent path
 * (INSTALL_ADDON / UNINSTALL_ADDON / GET_HELM_RELEASE_* / ROLLBACK_HELM_RELEASE / LIST_HELM_RELEASES) 로 라우팅.
 *
 * <p>새로운 helm CLI 호출이 필요할 때는 본 클래스에 args helper 추가 전에 PR 리뷰에서 "왜 in-process
 * 또는 cluster-agent 로 대체 불가능한가" 명시 필요.
 */
@Slf4j
@Component
public class HelmCommandExecutor {

    private static final Duration DEFAULT_TIMEOUT = Duration.ofMinutes(2);

    /**
     * Helm CLI 실행 + exit code 0 확인. 실패면 {@link HelmDeploymentException}.
     *
     * @param args              {@code helm} 뒤의 인자들 (e.g. {@code "template", "release", "/path/to/chart"}).
     * @param kubeconfigPath    nullable — kubeconfig 가 필요한 명령에만 (template 은 불요).
     * @return stdout (utf-8 텍스트). stderr 는 로그에만 보냄.
     */
    public String executeAndCheck(List<String> args, String kubeconfigPath) {
        List<String> cmd = new ArrayList<>();
        cmd.add("helm");
        cmd.addAll(args);
        CommandExecutionResult result = CommandExecutionSupport.execute(cmd, null, Map.of(), DEFAULT_TIMEOUT);
        if (!result.isSuccess()) {
            throw new HelmDeploymentException(
                    "helm " + String.join(" ", args) + " failed (exit " + result.exitCode() + "): " + result.stderr());
        }
        return result.stdout() == null ? "" : result.stdout();
    }

    /**
     * {@code helm template <release> <chartDir> --namespace <ns> --values <valuesFile>}
     *
     * <p>cluster 와 무관 — 로컬에서 chart 를 manifest YAML 로 렌더. caller (AgentChartRenderer) 가
     * 그 결과를 fabric8 server-side apply 로 cluster 에 적용.
     */
    public List<String> templateArgs(String releaseName, Path chartDir, String namespace, Path valuesFile) {
        List<String> args = new ArrayList<>();
        args.add("template");
        args.add(releaseName);
        args.add(chartDir.toString());
        if (namespace != null && !namespace.isBlank()) {
            args.add("--namespace");
            args.add(namespace);
        }
        if (valuesFile != null) {
            args.add("--values");
            args.add(valuesFile.toString());
        }
        return args;
    }
}
