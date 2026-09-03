package io.aipaas.cluster.provisioning.internal;

import com.pulumi.Context;
import com.pulumi.automation.AutomationException;
import com.pulumi.automation.ConfigValue;
import com.pulumi.automation.DestroyOptions;
import com.pulumi.automation.LocalWorkspace;
import com.pulumi.automation.LocalWorkspaceOptions;
import com.pulumi.automation.OperationType;
import com.pulumi.automation.OutputValue;
import com.pulumi.automation.PreviewResult;
import com.pulumi.automation.RefreshOptions;
import com.pulumi.automation.UpOptions;
import com.pulumi.automation.UpResult;
import com.pulumi.automation.UpdateResult;
import com.pulumi.automation.WorkspaceStack;
import io.aipaas.cluster.provisioning.api.exception.ProvisioningExecutionException;
import io.aipaas.cluster.provisioning.api.ProvisioningResult;
import io.aipaas.cluster.provisioning.api.ProvisioningRequest;
import io.aipaas.cluster.provisioning.api.ExecutionConfig;
import io.aipaas.cluster.provisioning.api.ProvisioningPreview;
import io.aipaas.cluster.provisioning.api.ProvisioningService;
import io.aipaas.cluster.provisioning.program.ProvisionerOrchestrator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * {@link ProvisioningService} 의 Pulumi Automation Java SDK 기반 구현.
 *
 * <p>{@link LocalWorkspace} + {@link WorkspaceStack} 으로 in-JVM Pulumi engine 호출. {@code pulumi}
 * binary 는 필요하지만 Pulumi 가 invoke 하는 language host 가 같은 JVM 안 {@link ProvisionerOrchestrator} 빈
 * — Go runtime 의존성 0.
 *
 * <p>CSP 자격증명은 process env 가 아닌 stack config (예: {@code aws:accessKey}) 로 분리 — state
 * backend env (호스트 AWS_*) 와 충돌 방지.
 */
@Slf4j
@RequiredArgsConstructor
public class AutomationProvisioningService implements ProvisioningService {

    private static final String PROJECT_NAME = "anycloud-k8s";

    private final ExecutionConfig config;
    private final ProvisionerOrchestrator program;
    private final ProvisioningResultMapper outputMapper;
    private final EngineEventAdapter eventAdapter;

    @Override
    public String buildStackName(ProvisioningRequest request) {
        return String.join(
                "-",
                config.getStackPrefix(),
                valueOrDefault(request.getProvider(), "unknown"),
                valueOrDefault(request.getEnvironment(), "dev"),
                valueOrDefault(request.getClusterName(), "cluster"));
    }

    /**
     * Pulumi 호출은 5–30분 걸릴 수 있어 caller TX 를 점유하면 DB connection pool 고갈. NOT_SUPPORTED 로
     * caller TX 와 분리 — 본 메서드 안에서 DB 접근 시 별도 connection 사용.
     */
    @Override
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public Map<String, Object> provision(ProvisioningRequest request) {
        assertEnabled();
        String stackName = buildStackName(request);
        String operationId = "provision-" + stackName + "-" + UUID.randomUUID();

        Map<String, String> rawCredEnv = request.credentialEnvironmentOrEmpty();
        Map<String, String> cspStackConfig =
                CspCredentialPulumiConfigMapper.toPulumiConfig(request.getProvider(), rawCredEnv);
        Map<String, String> envVars = buildEnvVars(rawCredEnv);

        Consumer<Context> programFn = ctx -> program.run(ctx, request);
        LocalWorkspaceOptions workspaceOpts = buildWorkspaceOptions(envVars);

        try (WorkspaceStack stack =
                LocalWorkspace.createOrSelectStack(PROJECT_NAME, stackName, programFn, workspaceOpts)) {
            applyConfig(stack, request, cspStackConfig);

            UpResult result = stack.up(UpOptions.builder()
                    .onEvent(event -> eventAdapter.publish(operationId, event))
                    .build());

            return unwrapOutputs(result.outputs());
        } catch (AutomationException e) {
            throw new ProvisioningExecutionException("Pulumi automation up failed: " + e.getMessage(), e);
        } catch (Exception e) {
            throw new ProvisioningExecutionException("Unexpected error during provision: " + e.getMessage(), e);
        }
    }

    @Override
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public ProvisioningPreview preview(ProvisioningRequest request) {
        assertEnabled();
        String stackName = buildStackName(request);

        Map<String, String> rawCredEnv = request.credentialEnvironmentOrEmpty();
        Map<String, String> cspStackConfig =
                CspCredentialPulumiConfigMapper.toPulumiConfig(request.getProvider(), rawCredEnv);
        Map<String, String> envVars = buildEnvVars(rawCredEnv);

        Consumer<Context> programFn = ctx -> program.run(ctx, request);
        LocalWorkspaceOptions workspaceOpts = buildWorkspaceOptions(envVars);

        try (WorkspaceStack stack =
                LocalWorkspace.createOrSelectStack(PROJECT_NAME, stackName, programFn, workspaceOpts)) {
            applyConfig(stack, request, cspStackConfig);
            PreviewResult result = stack.preview();
            return toProvisioningPreview(stackName, result);
        } catch (AutomationException e) {
            throw new ProvisioningExecutionException("Pulumi automation preview failed: " + e.getMessage(), e);
        } catch (Exception e) {
            throw new ProvisioningExecutionException("Unexpected error during preview: " + e.getMessage(), e);
        }
    }

    @Override
    public Map<String, Object> stackOutputs(String stackName, boolean showSecrets) {
        return stackOutputs(stackName, showSecrets, Map.of());
    }

    @Override
    public Map<String, Object> stackOutputs(
            String stackName, boolean showSecrets, Map<String, String> environmentOverrides) {
        assertEnabled();
        Map<String, String> sanitized = CspCredentialPulumiConfigMapper.stripCspEnv(environmentOverrides);
        Map<String, String> envVars = buildEnvVars(sanitized);
        LocalWorkspaceOptions workspaceOpts = buildWorkspaceOptions(envVars);

        // outputs 조회는 program 실행이 불필요 — noop program. 단, LocalWorkspace 는 program 이 필요해
        // null 불가 — empty Consumer 로 우회.
        Consumer<Context> noopProgram = ctx -> {};
        try (WorkspaceStack stack =
                LocalWorkspace.selectStack(PROJECT_NAME, stackName, noopProgram, workspaceOpts)) {
            return unwrapOutputs(stack.getOutputs());
        } catch (AutomationException e) {
            throw new ProvisioningExecutionException("Failed to read stack outputs: " + e.getMessage(), e);
        } catch (Exception e) {
            throw new ProvisioningExecutionException("Unexpected error reading outputs: " + e.getMessage(), e);
        }
    }

    @Override
    public ProvisioningResult typedStackOutputs(String stackName, Map<String, String> environmentOverrides) {
        Map<String, Object> raw = stackOutputs(stackName, true, environmentOverrides);
        return outputMapper.map(raw);
    }

    @Override
    public void destroy(String stackName) {
        destroy(stackName, Map.of());
    }

    @Override
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public void refresh(ProvisioningRequest request) {
        assertEnabled();
        String stackName = buildStackName(request);
        String operationId = "refresh-" + stackName + "-" + UUID.randomUUID();

        Map<String, String> rawCredEnv = request.credentialEnvironmentOrEmpty();
        Map<String, String> cspStackConfig =
                CspCredentialPulumiConfigMapper.toPulumiConfig(request.getProvider(), rawCredEnv);
        Map<String, String> envVars = buildEnvVars(rawCredEnv);

        Consumer<Context> programFn = ctx -> program.run(ctx, request);
        LocalWorkspaceOptions workspaceOpts = buildWorkspaceOptions(envVars);

        try (WorkspaceStack stack =
                LocalWorkspace.createOrSelectStack(PROJECT_NAME, stackName, programFn, workspaceOpts)) {
            applyConfig(stack, request, cspStackConfig);
            UpdateResult result = stack.refresh(RefreshOptions.builder()
                    .onEvent(event -> eventAdapter.publish(operationId, event))
                    .build());
            if (log.isDebugEnabled()) {
                log.debug("Refresh result: kind={}", result.summary().kind());
            }
        } catch (AutomationException e) {
            throw new ProvisioningExecutionException("Pulumi automation refresh failed: " + e.getMessage(), e);
        } catch (Exception e) {
            throw new ProvisioningExecutionException("Unexpected error during refresh: " + e.getMessage(), e);
        }
    }

    @Override
    public void removeStack(String stackName, Map<String, String> environmentOverrides) {
        assertEnabled();
        Map<String, String> sanitized = CspCredentialPulumiConfigMapper.stripCspEnv(environmentOverrides);
        Map<String, String> envVars = buildEnvVars(sanitized);
        LocalWorkspaceOptions workspaceOpts = buildWorkspaceOptions(envVars);

        // removeStack 은 program 무관 — state 파일만 삭제. noop program 사용.
        Consumer<Context> noopProgram = ctx -> {};
        try (WorkspaceStack stack =
                LocalWorkspace.selectStack(PROJECT_NAME, stackName, noopProgram, workspaceOpts)) {
            stack.workspace().removeStack(stackName);
            log.warn("removeStack: state file for stack {} removed (CSP resources not touched)", stackName);
        } catch (AutomationException e) {
            throw new ProvisioningExecutionException("Failed to remove stack state: " + e.getMessage(), e);
        } catch (Exception e) {
            throw new ProvisioningExecutionException("Unexpected error removing stack: " + e.getMessage(), e);
        }
    }

    @Override
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public void destroy(String stackName, Map<String, String> environmentOverrides) {
        assertEnabled();
        Map<String, String> sanitized = CspCredentialPulumiConfigMapper.stripCspEnv(environmentOverrides);
        Map<String, String> envVars = buildEnvVars(sanitized);
        LocalWorkspaceOptions workspaceOpts = buildWorkspaceOptions(envVars);
        String operationId = "destroy-" + stackName + "-" + UUID.randomUUID();

        // destroy 는 stack state 의 resource 목록을 사용하므로 program 재선언 없이 동작.
        Consumer<Context> noopProgram = ctx -> {};
        try (WorkspaceStack stack =
                LocalWorkspace.createOrSelectStack(PROJECT_NAME, stackName, noopProgram, workspaceOpts)) {
            UpdateResult destroyResult = stack.destroy(DestroyOptions.builder()
                    .onEvent(event -> eventAdapter.publish(operationId, event))
                    .build());
            if (log.isDebugEnabled()) {
                log.debug("Destroy result: kind={}", destroyResult.summary().kind());
            }
            stack.workspace().removeStack(stackName);
        } catch (AutomationException e) {
            throw new ProvisioningExecutionException("Pulumi automation destroy failed: " + e.getMessage(), e);
        } catch (Exception e) {
            throw new ProvisioningExecutionException("Unexpected error during destroy: " + e.getMessage(), e);
        }
    }

    // === helpers ===

    private Map<String, String> buildEnvVars(Map<String, String> overrides) {
        Map<String, String> envVars = new LinkedHashMap<>();
        if (config.getEnvironment() != null) {
            envVars.putAll(config.getEnvironment());
        }
        if (config.getPassphrase() != null && !config.getPassphrase().isBlank()) {
            envVars.put("PULUMI_CONFIG_PASSPHRASE", config.getPassphrase());
        }
        if (config.getBackendUrl() != null && !config.getBackendUrl().isBlank()) {
            envVars.put("PULUMI_BACKEND_URL", config.getBackendUrl());
        }
        // CSP 자격증명 (AWS_*/ARM_*/OS_* 등) 은 항상 strip — Pulumi binary 의 state backend 인증은
        // config.getEnvironment() 의 backend-scoped 키 (예: RustFS 의 AWS_*) 를 사용필요.
        // CSP API 호출용 자격증명은 별도로 stack config (aws:accessKey 등) 로 전달.
        if (overrides != null) {
            envVars.putAll(CspCredentialPulumiConfigMapper.stripCspEnv(overrides));
        }
        return envVars;
    }

    private LocalWorkspaceOptions buildWorkspaceOptions(Map<String, String> envVars) {
        LocalWorkspaceOptions.Builder builder = LocalWorkspaceOptions.builder().environmentVariables(envVars);
        if (config.getSecretsProvider() != null && !config.getSecretsProvider().isBlank()) {
            builder.secretsProvider(config.getSecretsProvider());
        }
        // Pulumi Java SDK 의 inline program 모드는 workDir 가 OS temp 에 자동 생성됨. infra/pulumi/
        // 디렉토리 의존을 제거하므로 명시 workDir 지정 X — Pulumi 가 stack 별 격리된 temp workspace 사용.
        // 단점: 동일 stackName 으로 동시 두 번 호출 시 race 가능 — 운영 caller (anycloud command service)
        // 가 transitionTo()/state machine 으로 동시 실행 차단.
        return builder.build();
    }

    private void applyConfig(WorkspaceStack stack, ProvisioningRequest request, Map<String, String> cspStackConfig)
            throws AutomationException {
        Map<String, ConfigValue> allConfig = new LinkedHashMap<>();

        // anycloud-k8s namespace — host 가 program 안에서 ctx.config().get(...) 으로 read.
        allConfig.put("anycloud-k8s:provider", new ConfigValue(valueOrDefault(request.getProvider(), "aws")));
        if (request.getClusterName() != null) {
            allConfig.put("anycloud-k8s:name", new ConfigValue(request.getClusterName()));
        }
        allConfig.put("anycloud-k8s:environment", new ConfigValue(valueOrDefault(request.getEnvironment(), "dev")));
        if (request.getRegion() != null && !request.getRegion().isBlank()) {
            allConfig.put("anycloud-k8s:region", new ConfigValue(request.getRegion()));
        }

        // request.config — caller 가 전달한 임의 key. secret 추정은 key 이름 기반 (defense-in-depth).
        for (Map.Entry<String, String> entry : request.configOrEmpty().entrySet()) {
            if (entry.getValue() == null || entry.getValue().isBlank()) {
                continue;
            }
            allConfig.put(entry.getKey(), new ConfigValue(entry.getValue(), isSecretKey(entry.getKey())));
        }

        // CSP 자격증명 — 표준 provider config key (aws:accessKey 등). 항상 secret.
        for (Map.Entry<String, String> entry : cspStackConfig.entrySet()) {
            allConfig.put(entry.getKey(), new ConfigValue(entry.getValue(), true));
        }

        stack.setAllConfig(allConfig);
    }

    private Map<String, Object> unwrapOutputs(Map<String, OutputValue> outputs) {
        Map<String, Object> result = new LinkedHashMap<>();
        outputs.forEach((k, v) -> result.put(k, v.value()));
        return result;
    }

    private ProvisioningPreview toProvisioningPreview(String stackName, PreviewResult result) {
        Map<String, Integer> summary = new LinkedHashMap<>();
        Map<OperationType, Integer> raw = result.changeSummary();
        if (raw != null) {
            raw.forEach((op, count) -> summary.put(op.name().toLowerCase(Locale.ROOT), count));
        }
        // Automation API PreviewResult 가 step list 미노출. changeSummary 의 hasChanges() 만 사용.
        return new ProvisioningPreview(stackName, true, summary, List.of());
    }

    private void assertEnabled() {
        if (!config.isEnabled()) {
            throw new IllegalStateException("Pulumi provisioning is disabled");
        }
    }

    private boolean isSecretKey(String key) {
        String normalized = key.toLowerCase(Locale.ROOT);
        return normalized.contains("password")
                || normalized.contains("secret")
                || normalized.contains("token")
                || normalized.contains("privatekey");
    }

    private String valueOrDefault(String value, String defaultValue) {
        return (value == null || value.isBlank()) ? defaultValue : value;
    }
}
