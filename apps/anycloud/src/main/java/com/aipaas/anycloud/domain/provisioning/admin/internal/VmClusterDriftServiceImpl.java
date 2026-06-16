package com.aipaas.anycloud.domain.provisioning.admin.internal;

import com.aipaas.anycloud.common.error.exception.ClusterNotFoundException;
import com.aipaas.anycloud.common.error.exception.provisioning.CspStderrClassifier;
import com.aipaas.anycloud.domain.credential.CspCredentialService;
import com.aipaas.anycloud.domain.provisioning.VmClusterEntity;
import com.aipaas.anycloud.domain.provisioning.VmClusterRepository;
import com.aipaas.anycloud.domain.provisioning.admin.VmClusterDriftService;
import io.aipaas.cluster.provisioning.core.PulumiPreviewResult;
import io.aipaas.cluster.provisioning.service.CspCredentialPulumiConfigMapper;
import io.aipaas.cluster.provisioning.service.PulumiCommandService;
import io.aipaas.cluster.provisioning.service.PulumiPreviewParser;
import io.aipaas.cluster.provisioning.service.PulumiStaleLockGuard;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * #7 — {@code AdminClusterDriftController} 의 직접 Repository / Pulumi 호출을 캡슐화.
 *
 * <p>TIMEOUT 상수 는 service 내부 정책 — controller 가 알 필요 없음.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class VmClusterDriftServiceImpl implements VmClusterDriftService {

    private static final Duration DRIFT_TIMEOUT = Duration.ofMinutes(10);
    private static final Duration REFRESH_TIMEOUT = Duration.ofMinutes(15);

    private final VmClusterRepository vmClusterRepository;
    private final CspCredentialService cspCredentialService;
    private final PulumiCommandService pulumiCommandService;
    private final PulumiStaleLockGuard staleLockGuard;

    @Override
    public Map<String, Object> detectDrift(String clusterName) {
        VmClusterEntity vmCluster = requireVmClusterWithStack(clusterName);
        Map<String, String> env = sanitizedEnvironment(vmCluster);
        String stackName = vmCluster.getStackName();

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("clusterName", clusterName);
        result.put("stackName", stackName);

        var select = pulumiCommandService.selectStack(stackName, env);
        if (!select.isSuccess()) {
            throw CspStderrClassifier.classifyPulumi("stack select", select.getStderr());
        }
        var preview = staleLockGuard.run(
                stackName,
                env,
                () -> pulumiCommandService.run(List.of("preview", "--refresh", "--json"), DRIFT_TIMEOUT, env));
        if (!preview.isSuccess()) {
            throw CspStderrClassifier.classifyPulumi("drift detection", preview.getStderr());
        }
        PulumiPreviewResult parsed = PulumiPreviewParser.parse(stackName, true, preview.getStdout());
        result.put("drifted", parsed.hasChanges());
        result.put("changeSummary", parsed.changeSummary());
        result.put(
                "steps",
                parsed.steps().stream()
                        .filter(s -> !"same".equals(s.op()))
                        .map(s -> Map.of(
                                "op", String.valueOf(s.op()),
                                "type", String.valueOf(s.type()),
                                "name", String.valueOf(s.name())))
                        .toList());
        return result;
    }

    @Override
    public Map<String, Object> refreshState(String clusterName) {
        VmClusterEntity vmCluster = requireVmClusterWithStack(clusterName);
        Map<String, String> env = sanitizedEnvironment(vmCluster);
        String stackName = vmCluster.getStackName();

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("clusterName", clusterName);
        result.put("stackName", stackName);

        var select = pulumiCommandService.selectStack(stackName, env);
        if (!select.isSuccess()) {
            throw CspStderrClassifier.classifyPulumi("stack select", select.getStderr());
        }
        var refresh = staleLockGuard.run(
                stackName, env, () -> pulumiCommandService.run(List.of("refresh", "--yes"), REFRESH_TIMEOUT, env));
        result.put("success", refresh.isSuccess());
        result.put("exitCode", refresh.getExitCode());
        if (!refresh.isSuccess()) {
            log.warn("Pulumi refresh failed for stack {}: {}", stackName, refresh.getStderr());
            throw CspStderrClassifier.classifyPulumi("refresh", refresh.getStderr());
        }
        log.info("Pulumi state refreshed for stack {} (cluster {})", stackName, clusterName);
        return result;
    }

    private VmClusterEntity requireVmClusterWithStack(String clusterName) {
        VmClusterEntity vmCluster = vmClusterRepository
                .findFirstByClusterNameOrderByCreatedAtDesc(clusterName)
                .orElseThrow(() -> new ClusterNotFoundException(clusterName));
        if (vmCluster.getStackName() == null || vmCluster.getStackName().isBlank()) {
            throw new ClusterNotFoundException(clusterName + " (no Pulumi stack)");
        }
        return vmCluster;
    }

    /**
     * CSP credential env 를 strip — process env 는 state backend (RustFS) 자격증명만 유지.
     * CSP 자격증명은 provision 시 stack config 로 영속화되어 Pulumi 가 자동 사용.
     */
    private Map<String, String> sanitizedEnvironment(VmClusterEntity vmCluster) {
        Map<String, String> raw = cspCredentialService.resolveEnvironment(
                vmCluster.getClusterProvider(), vmCluster.getCredentialId(), vmCluster.getCredentialSourceType());
        return CspCredentialPulumiConfigMapper.stripCspEnv(raw);
    }
}
