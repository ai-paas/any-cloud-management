package com.aipaas.anycloud.domain.provisioning.preflight.internal;

import com.aipaas.anycloud.common.error.enums.ErrorCode;
import com.aipaas.anycloud.common.error.exception.CustomException;
import com.aipaas.anycloud.domain.provisioning.preflight.validation.ProvisioningConfigRules;
import com.aipaas.anycloud.domain.provisioning.preflight.validation.ProvisioningCredentialRules;
import com.aipaas.anycloud.domain.provisioning.preflight.validation.ProvisioningProviderValidator;
import com.aipaas.anycloud.domain.vmoptions.validation.VmOptionsSelectionValidator;
import com.aipaas.anycloud.domain.cluster.ClusterRepository;
import com.aipaas.anycloud.domain.credential.CspCredentialService;
import com.aipaas.anycloud.domain.credential.ResolvedCspCredential;
import com.aipaas.anycloud.domain.credential.model.CspCredentialSourceType;
import com.aipaas.anycloud.domain.provisioning.pricing.CostEstimate;
import com.aipaas.anycloud.domain.provisioning.pricing.CostEstimator;
import com.aipaas.anycloud.domain.provisioning.VmClusterRepository;
import com.aipaas.anycloud.domain.provisioning.api.request.ProvisionClusterRequest;
import com.aipaas.anycloud.domain.provisioning.api.response.VmClusterPreflightResponse;
import com.aipaas.anycloud.domain.provisioning.model.SupportedProvisioningProvider;
import com.aipaas.anycloud.domain.provisioning.model.VmClusterPreflightIssue;
import com.aipaas.anycloud.domain.provisioning.preflight.VmClusterPreflightService;
import com.aipaas.anycloud.domain.provisioning.properties.PulumiProperties;
import com.aipaas.anycloud.domain.vmoptions.VmOptionsQueryService;
import io.aipaas.cluster.provisioning.core.ProvisioningRequest;
import io.aipaas.cluster.provisioning.service.PulumiProvisioningService;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * VM cluster preflight 검증 service — VmClusterQueryService 의 delegation 대상.
 *
 * <p>preflightVmCluster 가 7 단계로 분해됨 (각 단계마다 helper + result record):
 *
 * <ol>
 *   <li>{@code normalizeProviderAndDefaults} — provider lookup + default config 적용.</li>
 *   <li>{@code checkClusterNameConflict} — 등록된 cluster / 진행 중 workflow 충돌.</li>
 *   <li>{@code resolveCredentialAndValidate} — credential resolve + 필수 config + selection.</li>
 *   <li>Inline — required credential keys + missing detection.</li>
 *   <li>{@code checkVmOptionsDiscovery} — CSP region listing + selection 매칭.</li>
 *   <li>{@code assessProviderReadiness} — CSP 별 readiness checklist.</li>
 *   <li>{@code buildStackNamePreview} — Pulumi stack name 미리보기.</li>
 * </ol>
 *
 * <p>각 helper 의 결과는 record (ProviderNormalizationResult / CredentialAndValidationResult /
 * VmOptionsDiscoveryResult / StackNamePreview / ProviderReadinessAssessment) 로 packing 되어
 * caller 가 응답 envelope 에 누적. 분해는 완료된 상태로 추가 분리는 무의미.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class VmClusterPreflightServiceImpl implements VmClusterPreflightService {

    private final ClusterRepository clusterRepository;
    private final VmClusterRepository vmClusterRepository;
    private final CspCredentialService cspCredentialService;
    private final PulumiProvisioningService pulumiProvisioningService;
    private final VmOptionsQueryService vmOptionsQueryService;
    private final VmOptionsSelectionValidator vmOptionsSelectionValidator;
    private final PulumiProperties pulumiProperties;
    private final CostEstimator costEstimator;
    private final ProvisioningProviderValidator provisioningProviderValidator;

    /**
     * Pulumi preview 기반 create 미리보기. create 와 동일한 정적 검증 + credential 해석 후
     * {@code pulumi preview --json} 실행 — CSP 자원은 생성하지 않는다.
     */
    @Override
    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.NOT_SUPPORTED)
    public com.aipaas.anycloud.domain.provisioning.api.response.VmClusterPreviewResponse previewVmCluster(
            ProvisionClusterRequest cluster) {
        ResolvedCspCredential credential =
                cspCredentialService.resolveForProvision(cluster.getClusterProvider(), cluster.getCredentialId());
        ProvisioningRequest request = provisioningProviderValidator.validateStaticAndBuildRequest(cluster, credential);
        return com.aipaas.anycloud.domain.provisioning.api.response.VmClusterPreviewResponse.from(
                pulumiProvisioningService.preview(request));
    }

    @Override
    public VmClusterPreflightResponse preflightVmCluster(ProvisionClusterRequest cluster) {
        List<String> warnings = new ArrayList<>();
        List<String> errors = new ArrayList<>();
        List<VmClusterPreflightIssue> warningItems = new ArrayList<>();
        List<VmClusterPreflightIssue> errorItems = new ArrayList<>();
        Map<String, String> rawConfig = ProvisioningProviderValidator.normalizeConfig(cluster);
        Map<String, String> normalizedConfig = new LinkedHashMap<>(rawConfig);
        List<String> appliedDefaults = new ArrayList<>();
        SupportedProvisioningProvider provider = null;
        ResolvedCspCredential resolvedCredential = null;
        String stackName = null;
        boolean credentialResolved = false;
        boolean existingClusterConflict = false;
        boolean vmOptionsDiscoveryChecked = false;
        boolean vmOptionsDiscoveryReady = false;
        List<String> vmOptionsDiscoveryMessages = new ArrayList<>();
        boolean providerReadinessChecked = false;
        boolean providerReadinessReady = false;
        List<String> providerReadinessMessages = new ArrayList<>();
        List<String> e2eChecklistItems = new ArrayList<>();

        // ─── Step 1: Provider normalize + config defaults — helper 위임 ───────────────
        ProviderNormalizationResult normalization = normalizeProviderAndDefaults(cluster, rawConfig, normalizedConfig);
        provider = normalization.provider();
        appliedDefaults.addAll(normalization.appliedDefaults());
        if (normalization.error() != null) {
            addError(errors, errorItems, "INVALID_PROVIDER", normalization.error(), "clusterProvider");
        }

        // ─── Step 2: Cluster name conflict — helper 위임 ──────────────────────────────
        existingClusterConflict = checkClusterNameConflict(cluster);
        if (existingClusterConflict) {
            addError(
                    errors,
                    errorItems,
                    "CLUSTER_NAME_CONFLICT",
                    "Cluster name is already in use by a registered cluster or active VM workflow",
                    "clusterName");
        }

        // ─── Step 3: Credential resolve + config + selection validate — helper 위임 ───
        if (provider != null) {
            CredentialAndValidationResult result = resolveCredentialAndValidate(provider, cluster, normalizedConfig);
            resolvedCredential = result.credential();
            credentialResolved = result.credentialResolved();
            for (PreflightIssueData issue : result.issues()) {
                addError(errors, errorItems, issue.code(), issue.message(), issue.field());
            }
        }

        // ─── Step 4: Required credential keys + missing detection ────────────────────
        List<String> requiredCredentialKeys =
                provider == null ? List.of() : ProvisioningCredentialRules.requiredCredentialKeys(provider);
        List<String> missingCredentialKeys =
                provider == null ? List.of() : missingCredentialKeys(provider, resolvedCredential);

        if (provider != null && !missingCredentialKeys.isEmpty()) {
            addWarning(
                    warnings,
                    warningItems,
                    "MISSING_CREDENTIALS",
                    "Credential is not ready for provisioning yet",
                    "credentialId");
        }

        // ─── Step 5: VM options discovery (region 매칭 포함) — helper 위임 ────────────
        if (provider != null && missingCredentialKeys.isEmpty()) {
            vmOptionsDiscoveryChecked = true;
            VmOptionsDiscoveryResult discovery = checkVmOptionsDiscovery(provider, cluster);
            vmOptionsDiscoveryReady = discovery.ready();
            vmOptionsDiscoveryMessages.addAll(discovery.messages());
            if (discovery.error() != null) {
                addError(errors, errorItems, "VM_OPTIONS_DISCOVERY_FAILED", discovery.error(), "region");
            }
        }

        // ─── Step 6: Provider readiness assessment ───────────────────────────────────
        if (provider != null) {
            providerReadinessChecked = true;
            ProviderReadinessAssessment providerReadiness =
                    assessProviderReadiness(provider, cluster, normalizedConfig, missingCredentialKeys);
            providerReadinessReady = providerReadiness.ready();
            providerReadinessMessages.addAll(providerReadiness.messages());
            e2eChecklistItems.addAll(providerReadiness.e2eChecklistItems());
            warnings.addAll(providerReadiness.warnings());
            warningItems.addAll(providerReadiness.warningItems());
        }

        // ─── Step 7: Stack name preview — helper 위임 ─────────────────────────────────
        if (provider != null
                && errors.stream().noneMatch(message -> message != null && message.contains("Provider is blank"))) {
            StackNamePreview preview = buildStackNamePreview(provider, cluster, resolvedCredential, normalizedConfig);
            stackName = preview.stackName();
            if (preview.warning() != null) {
                addWarning(
                        warnings,
                        warningItems,
                        "STACK_PREVIEW_UNAVAILABLE",
                        "Unable to build stack preview: " + preview.warning(),
                        "clusterName");
            }
        }

        // 정적 catalog 기반 예상 비용.
        String useSpotRaw = normalizedConfig.get("useSpot");
        if (useSpotRaw == null) {
            useSpotRaw = normalizedConfig.getOrDefault("anycloud-k8s:useSpot", "false");
        }
        CostEstimate costEstimate = costEstimator.estimate(
                provider == null ? cluster.getClusterProvider() : provider.getCanonicalName(),
                normalizedConfig,
                Boolean.parseBoolean(useSpotRaw));

        return VmClusterPreflightResponse.builder()
                .readyToProvision(errors.isEmpty() && missingCredentialKeys.isEmpty())
                .existingClusterConflict(existingClusterConflict)
                .provider(provider == null ? cluster.getClusterProvider() : provider.getCanonicalName())
                .clusterName(cluster == null ? null : cluster.getClusterName())
                .environment(cluster == null ? null : cluster.getEnvironment())
                .region(cluster == null ? null : cluster.getRegion())
                .stackName(stackName)
                .credentialId(
                        resolvedCredential == null ? cluster.getCredentialId() : resolvedCredential.getCredentialId())
                .credentialName(
                        resolvedCredential == null
                                ? (cluster.getCredentialId() == null
                                                || cluster.getCredentialId().isBlank()
                                        ? "Application Environment"
                                        : null)
                                : resolvedCredential.getCredentialName())
                .credentialSourceType(
                        resolvedCredential == null
                                ? (cluster.getCredentialId() == null
                                                || cluster.getCredentialId().isBlank()
                                        ? CspCredentialSourceType.ENV
                                        : null)
                                : resolvedCredential.getSourceType())
                .credentialResolved(credentialResolved)
                .requiredCredentialKeys(requiredCredentialKeys)
                .missingCredentialKeys(missingCredentialKeys)
                .vmOptionsDiscoveryChecked(vmOptionsDiscoveryChecked)
                .vmOptionsDiscoveryReady(vmOptionsDiscoveryReady)
                .vmOptionsDiscoveryMessages(vmOptionsDiscoveryMessages)
                .providerReadinessChecked(providerReadinessChecked)
                .providerReadinessReady(providerReadinessReady)
                .providerReadinessMessages(providerReadinessMessages)
                .e2eChecklistItems(e2eChecklistItems)
                .normalizedConfig(normalizedConfig)
                .appliedDefaults(appliedDefaults)
                .warnings(warnings)
                .errors(errors)
                .warningItems(warningItems)
                .errorItems(errorItems)
                .costEstimate(costEstimate)
                .build();
    }

    /**
     * Step 1 helper. provider normalize + config defaults 적용. defaults 가 raw config 에 없던
     * key 면 appliedDefaults 에 누적 (응답으로 사용자에게 노출).
     *
     * <p>provider 가 invalid 면 result.error() 가 non-null — caller 가 errors 에 추가.
     */
    private ProviderNormalizationResult normalizeProviderAndDefaults(
            ProvisionClusterRequest cluster, Map<String, String> rawConfig, Map<String, String> normalizedConfig) {
        List<String> applied = new ArrayList<>();
        try {
            SupportedProvisioningProvider provider =
                    ProvisioningProviderValidator.normalizeProvider(cluster.getClusterProvider());
            ProvisioningConfigRules.applyDefaults(provider, normalizedConfig);
            normalizedConfig.forEach((key, value) -> {
                if (!rawConfig.containsKey(key)) {
                    applied.add(key);
                }
            });
            return new ProviderNormalizationResult(provider, applied, null);
        } catch (Exception e) {
            return new ProviderNormalizationResult(null, applied, e.getMessage());
        }
    }

    /**
     * Step 2 helper. cluster name 이 이미 등록된 cluster (clusters) 또는 진행 중인 VM workflow
     * (vm_clusters.active_request_key) 와 충돌하는지 확인.
     *
     * <p>cluster 또는 clusterName blank 면 conflict false (caller 가 name 검증 별도).
     */
    private boolean checkClusterNameConflict(ProvisionClusterRequest cluster) {
        if (cluster == null
                || cluster.getClusterName() == null
                || cluster.getClusterName().isBlank()) {
            return false;
        }
        return clusterRepository.findById(cluster.getClusterName()).isPresent()
                || vmClusterRepository.existsByActiveRequestKey(cluster.getClusterName());
    }

    /**
     * Step 3 helper. credential resolve + required config validate + selection validate 3 개의
     * 동일 try-catch 패턴 (try → catch → addError(code, msg, field)) 을 한 곳에서 처리. 메인 메서드의
     * mutable 변수 (resolvedCredential / credentialResolved) 는 {@link CredentialAndValidationResult}
     * 로 반환.
     *
     * <p>각 단계가 독립 — 한 단계가 실패해도 다음 단계 계속 (preflight 의 의도: 모든 에러 누적).
     */
    private CredentialAndValidationResult resolveCredentialAndValidate(
            SupportedProvisioningProvider provider,
            ProvisionClusterRequest cluster,
            Map<String, String> normalizedConfig) {
        ResolvedCspCredential credential = null;
        boolean credentialResolved = false;
        List<PreflightIssueData> issues = new ArrayList<>();

        try {
            credential =
                    cspCredentialService.resolveForProvision(provider.getCanonicalName(), cluster.getCredentialId());
            credentialResolved = true;
        } catch (Exception e) {
            issues.add(new PreflightIssueData("CREDENTIAL_RESOLUTION_FAILED", e.getMessage(), "credentialId"));
        }

        try {
            ProvisioningConfigRules.validateRequiredConfig(provider, normalizedConfig);
        } catch (Exception e) {
            issues.add(new PreflightIssueData("INVALID_CONFIG", e.getMessage(), "config"));
        }

        try {
            ProvisioningProviderValidator.validateSelections(
                    cluster, provider, vmOptionsSelectionValidator, normalizedConfig);
        } catch (Exception e) {
            issues.add(new PreflightIssueData("INVALID_SELECTION", e.getMessage(), "config"));
        }

        return new CredentialAndValidationResult(credential, credentialResolved, issues);
    }

    /**
     * Step 5 helper. VM options discovery 호출 + region 매칭 검증.
     *
     * <p>region 이 명시되면 listRegions 결과와 case-insensitive 비교. 매칭 실패 시 invalid region
     * 으로 throw → catch 가 error 응답으로 변환. 정상 응답이면 ready=true + 친화적 메시지.
     */
    private VmOptionsDiscoveryResult checkVmOptionsDiscovery(
            SupportedProvisioningProvider provider, ProvisionClusterRequest cluster) {
        List<String> messages = new ArrayList<>();
        try {
            List<com.aipaas.anycloud.domain.vmoptions.api.VmOptionRegion> regions =
                    vmOptionsQueryService.listRegions(provider.getCanonicalName(), null);
            if (cluster != null
                    && cluster.getRegion() != null
                    && !cluster.getRegion().isBlank()) {
                boolean matchedRegion =
                        regions.stream().anyMatch(item -> cluster.getRegion().equalsIgnoreCase(item.getId()));
                if (!matchedRegion) {
                    throw new CustomException(
                            ErrorCode.INVALID_INPUT_VALUE,
                            "region",
                            cluster.getRegion(),
                            "Selected region was not found in VM options discovery");
                }
            }
            messages.add("VM options discovery is reachable for provider " + provider.getCanonicalName());
            return new VmOptionsDiscoveryResult(true, messages, null);
        } catch (Exception e) {
            String message = "VM options discovery check failed: " + e.getMessage();
            messages.add(message);
            return new VmOptionsDiscoveryResult(false, messages, message);
        }
    }

    /**
     * Step 7 helper. Pulumi 가 사용할 stackName 미리 계산. credential 이 미해결인 경우
     * dummy ResolvedCspCredential (ENV / Application Environment) 로 채워 stackName builder 가
     * 어쨌든 동작하도록 한다.
     */
    private StackNamePreview buildStackNamePreview(
            SupportedProvisioningProvider provider,
            ProvisionClusterRequest cluster,
            ResolvedCspCredential resolvedCredential,
            Map<String, String> normalizedConfig) {
        try {
            ResolvedCspCredential previewCredential = resolvedCredential != null
                    ? resolvedCredential
                    : ResolvedCspCredential.builder()
                            .credentialId(cluster.getCredentialId())
                            .credentialName(
                                    cluster.getCredentialId() == null
                                                    || cluster.getCredentialId().isBlank()
                                            ? "Application Environment"
                                            : null)
                            .sourceType(
                                    cluster.getCredentialId() == null
                                                    || cluster.getCredentialId().isBlank()
                                            ? CspCredentialSourceType.ENV
                                            : null)
                            .environment(Map.of())
                            .build();
            ProvisioningRequest request = ProvisioningRequest.builder()
                    .provider(provider.getCanonicalName())
                    .clusterName(cluster.getClusterName())
                    .environment(cluster.getEnvironment())
                    .region(cluster.getRegion())
                    .credentialId(previewCredential.getCredentialId())
                    .credentialName(previewCredential.getCredentialName())
                    .config(normalizedConfig)
                    .credentialEnvironment(previewCredential.environmentOrEmpty())
                    .build();
            return new StackNamePreview(pulumiProvisioningService.buildStackName(request), null);
        } catch (Exception e) {
            return new StackNamePreview(null, e.getMessage());
        }
    }

    private List<String> missingCredentialKeys(
            SupportedProvisioningProvider provider, ResolvedCspCredential credential) {
        Map<String, String> provided = credential == null ? Map.of() : credential.environmentOrEmpty();
        return switch (provider) {
            case GCP -> (ProvisioningCredentialRules.hasCredentialValue(
                                    "GOOGLE_CREDENTIALS", provided, pulumiProperties)
                            || ProvisioningCredentialRules.hasCredentialValue(
                                    "GOOGLE_APPLICATION_CREDENTIALS", provided, pulumiProperties))
                    ? List.of()
                    : List.of("GOOGLE_CREDENTIALS or GOOGLE_APPLICATION_CREDENTIALS");
            case OCI -> {
                List<String> missing = new ArrayList<>();
                for (String key :
                        List.of("TF_VAR_tenancy_ocid", "TF_VAR_user_ocid", "TF_VAR_fingerprint", "TF_VAR_region")) {
                    if (!ProvisioningCredentialRules.hasCredentialValue(key, provided, pulumiProperties)) {
                        missing.add(key);
                    }
                }
                if (!ProvisioningCredentialRules.hasCredentialValue("TF_VAR_private_key", provided, pulumiProperties)
                        && !ProvisioningCredentialRules.hasCredentialValue(
                                "TF_VAR_private_key_path", provided, pulumiProperties)) {
                    missing.add("TF_VAR_private_key or TF_VAR_private_key_path");
                }
                yield missing;
            }
            case DIGITALOCEAN -> (ProvisioningCredentialRules.hasCredentialValue(
                                    "DIGITALOCEAN_TOKEN", provided, pulumiProperties)
                            || ProvisioningCredentialRules.hasCredentialValue(
                                    "DIGITALOCEAN_ACCESS_TOKEN", provided, pulumiProperties))
                    ? List.of()
                    : List.of("DIGITALOCEAN_TOKEN or DIGITALOCEAN_ACCESS_TOKEN");
            default -> ProvisioningCredentialRules.requiredCredentialKeys(provider).stream()
                    .filter(key -> !ProvisioningCredentialRules.hasCredentialValue(key, provided, pulumiProperties))
                    .toList();
        };
    }

    private ProviderReadinessAssessment assessProviderReadiness(
            SupportedProvisioningProvider provider,
            ProvisionClusterRequest cluster,
            Map<String, String> normalizedConfig,
            List<String> missingCredentialKeys) {
        List<String> messages = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        List<VmClusterPreflightIssue> warningItems = new ArrayList<>();
        List<String> checklistItems = new ArrayList<>();

        checklistItems.add("Confirm rabbitmq, anycloud-backend, and anycloud-bootstrap-worker are running");
        checklistItems.add("Verify Pulumi CLI and provider credentials are available to the backend runtime");
        checklistItems.add(
                "Review bootstrap prerequisites such as outbound package repository access and SSH reachability");

        if (!missingCredentialKeys.isEmpty()) {
            warnings.add("Provider credential is incomplete, so E2E validation will stop before provisioning");
            warningItems.add(buildIssue(
                    "MISSING_CREDENTIALS",
                    "Provider credential is incomplete, so E2E validation will stop before provisioning",
                    "credentialId"));
            messages.add("Missing credential keys: " + String.join(", ", missingCredentialKeys));
        } else {
            messages.add("Credential prerequisites are satisfied for provider " + provider.getCanonicalName());
        }

        if (cluster != null
                && cluster.getRegion() != null
                && !cluster.getRegion().isBlank()) {
            checklistItems.add("Verify quotas and image/spec availability in region " + cluster.getRegion());
        }

        String masterSpec = normalizedConfig.get("masterInstanceType");
        String workerSpec = normalizedConfig.get("workerInstanceType");
        if (masterSpec != null && !masterSpec.isBlank()) {
            checklistItems.add("Confirm master spec is available: " + masterSpec);
        }
        if (workerSpec != null && !workerSpec.isBlank()) {
            checklistItems.add("Confirm worker spec is available: " + workerSpec);
        }

        switch (provider) {
            case AWS -> {
                messages.add(
                        "AWS preflight can validate VM options live, but IAM permissions and EC2/VPC quotas still need runtime confirmation");
                checklistItems.add("Verify EC2, VPC, EIP, and route-table quotas for the requested node count");
            }
            case GCP -> {
                messages.add(
                        "GCP preflight validates VM options, but Compute Engine API enablement and regional quota must be confirmed");
                checklistItems.add("Verify Compute Engine API is enabled for the selected project");
                checklistItems.add("Confirm project and region quotas allow the requested VM count");
            }
            case AZURE -> {
                messages.add(
                        "Azure preflight validates VM options, but subscription quota and resource-group permissions still need confirmation");
                checklistItems.add("Confirm the configured Azure resource group exists or can be created");
                checklistItems.add("Verify VM family quota in the selected Azure region");
            }
            case ALIBABA -> {
                messages.add(
                        "Alibaba preflight validates live VM options, but ECS zone/image availability and quota should be verified before E2E");
                checklistItems.add(
                        "Verify ECS instance family and Ubuntu image availability in the selected region or zone");
            }
            case OPENSTACK -> {
                messages.add(
                        "OpenStack preflight validates live VM options, but image/flavor names and floating IP capacity must be confirmed in the target tenant");
                checklistItems.add("Confirm configured OpenStack image and flavor names exist");
                checklistItems.add("Verify external network and floating IP pool capacity");
            }
            case PROXMOX -> {
                messages.add(
                        "Proxmox preflight validates live options against the target environment, but template/snippet storage and network bridge readiness remain manual checks");
                checklistItems.add(
                        "Confirm template VM, snippet-capable datastore, and network bridge exist on the selected Proxmox node");
                checklistItems.add("Verify selected subnet has gateway and IP space for master and worker nodes");
            }
            case OCI -> {
                messages.add(
                        "OCI preflight validates live VM options, but compartment, image, and shape availability can still vary by tenancy and region");
                checklistItems.add("Confirm compartment access and shape availability in the selected OCI region");
                checklistItems.add(
                        "Verify Ubuntu image availability or adjust the image lookup if tenancy policy differs");
            }
            case DIGITALOCEAN -> {
                messages.add(
                        "DigitalOcean preflight validates live VM options, but Droplet/VPC quota and token scope still need runtime confirmation");
                checklistItems.add("Confirm the API token can manage Droplets, VPCs, and SSH keys");
            }
        }

        checklistItems = checklistItems.stream()
                .filter(Objects::nonNull)
                .map(String::trim)
                .filter(item -> !item.isBlank())
                .distinct()
                .toList();
        messages = messages.stream()
                .filter(Objects::nonNull)
                .map(String::trim)
                .filter(item -> !item.isBlank())
                .distinct()
                .toList();
        warnings = warnings.stream()
                .filter(Objects::nonNull)
                .map(String::trim)
                .filter(item -> !item.isBlank())
                .distinct()
                .toList();

        return new ProviderReadinessAssessment(
                missingCredentialKeys.isEmpty(), messages, warnings, checklistItems, warningItems);
    }

    private void addWarning(
            List<String> warnings,
            List<VmClusterPreflightIssue> warningItems,
            String code,
            String message,
            String field) {
        warnings.add(message);
        warningItems.add(buildIssue(code, message, field));
    }

    private void addError(
            List<String> errors, List<VmClusterPreflightIssue> errorItems, String code, String message, String field) {
        errors.add(message);
        errorItems.add(buildIssue(code, message, field));
    }

    private VmClusterPreflightIssue buildIssue(String code, String message, String field) {
        return VmClusterPreflightIssue.builder()
                .code(code)
                .message(message)
                .field(field)
                .build();
    }

    private record ProviderReadinessAssessment(
            boolean ready,
            List<String> messages,
            List<String> warnings,
            List<String> e2eChecklistItems,
            List<VmClusterPreflightIssue> warningItems) {}

    /** F-D — Step 5 helper 결과. error 가 non-null 이면 caller 가 errors 에 추가. */
    private record VmOptionsDiscoveryResult(boolean ready, List<String> messages, String error) {}

    /** F-D — Step 7 helper 결과. warning 이 non-null 이면 caller 가 warnings 에 추가. */
    private record StackNamePreview(String stackName, String warning) {}

    /** G-B — Step 3 helper 결과. issues 는 caller 가 errors 에 일괄 추가. */
    private record CredentialAndValidationResult(
            ResolvedCspCredential credential, boolean credentialResolved, List<PreflightIssueData> issues) {}

    /** G-B — preflight issue 의 raw data. caller 가 addError / addWarning 로 누적. */
    private record PreflightIssueData(String code, String message, String field) {}

    /**
     * I-A — Step 1 helper 결과. provider 가 null 이면 정규화 실패. error 가 non-null 이면 caller 가
     * errors 에 추가 (INVALID_PROVIDER).
     */
    private record ProviderNormalizationResult(
            SupportedProvisioningProvider provider, List<String> appliedDefaults, String error) {}
}
