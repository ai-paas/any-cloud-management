package com.aipaas.anycloud.domain.cluster.internal;

import com.aipaas.anycloud.domain.cluster.ClusterProvider;
import com.aipaas.anycloud.domain.cluster.api.request.CreateClusterRequest;
import com.aipaas.anycloud.domain.cluster.mapper.ClusterSpecMapper;
import com.aipaas.anycloud.domain.cluster.model.VmClusterSpec;
import com.aipaas.anycloud.domain.operation.OperationEntity;
import com.aipaas.anycloud.domain.operation.OperationService;
import com.aipaas.anycloud.domain.operation.model.OperationType;
import com.aipaas.anycloud.domain.provisioning.VmClusterService;
import com.aipaas.anycloud.domain.provisioning.api.request.ProvisionClusterRequest;
import com.aipaas.anycloud.domain.provisioning.bootstrap.KubeadmJoinTokens;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * VM source cluster 생성 strategy.
 * <p>
 * spec 의 weak typing (Map&lt;String, Object&gt;) 은 {@link ClusterSpecMapper} 가 service
 * 진입 시점에 typed {@link VmClusterSpec} record 로 변환 — typo / 누락 field 가 즉시 IllegalArg
 * 으로 잡힘 + provider 내부에선 {@code spec.field()} 로 type-safe 접근.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class VmClusterProviderImpl implements ClusterProvider {

    private static final int CREATE_TOTAL_STEPS = 3; // PROVISION → BOOTSTRAP → VERIFY
    private static final String CONFIG_JOIN_TOKEN = "anycloud-k8s:joinToken";

    private final VmClusterService vmClusterService;
    private final OperationService operationService;
    private final ObjectMapper objectMapper;

    @Override
    public String source() {
        return "vm";
    }

    @Override
    public OperationEntity create(CreateClusterRequest request) {
        // Map → typed record. 변환 자체가 validation 1 단계 (필수 field missing → 400).
        VmClusterSpec spec = ClusterSpecMapper.toVm(request.getSpec());

        String resourcePayload = serialize(request);
        OperationEntity op = operationService.start(
                OperationType.CREATE_CLUSTER, "cluster", request.getClusterName(), resourcePayload, CREATE_TOTAL_STEPS);
        try {
            ProvisionClusterRequest dto = toProvisionDto(request.getClusterName(), spec);
            vmClusterService.createVmCluster(dto);
            operationService.markRunning(op.getId());
        } catch (Exception e) {
            operationService.fail(op.getId(), e.getMessage());
            throw e;
        }
        return op;
    }

    private ProvisionClusterRequest toProvisionDto(String clusterName, VmClusterSpec spec) {
        ProvisionClusterRequest dto = new ProvisionClusterRequest();
        dto.setClusterName(clusterName);
        dto.setClusterProvider(spec.provider());
        dto.setRegion(spec.region());
        dto.setEnvironment(spec.environment());
        dto.setCredentialId(spec.credentialId());

        // hasGpuNodes flag 그대로 보존 (cluster.has_gpu_nodes 컬럼에 반영).
        dto.setHasGpuNodes(spec.hasGpuNodes());

        // config 를 mutable copy 로 받아 GPU defaults 주입. 운영자 명시 값은 보존.
        java.util.Map<String, String> config =
                spec.config() == null ? new java.util.HashMap<>() : new java.util.HashMap<>(spec.config());
        if (Boolean.TRUE.equals(spec.hasGpuNodes())) {
            com.aipaas.anycloud.domain.provisioning.mapper.GpuFlavorMapper.applyGpuDefaults(spec.provider(), config);
        }
        // typed VmClusterSpec 필드를 config map 에 주입.
        // Pulumi provider 가 config map 의 key 로 읽음. 운영자가 직접 config 에 명시한 값이 있으면
        // 보존 (typed 필드는 보조 — 운영자 입력 우선).
        if (Boolean.TRUE.equals(spec.useSpot()) && !config.containsKey("useSpot")) {
            config.put("useSpot", "true");
        }
        if (spec.osImage() != null && !spec.osImage().isBlank() && !config.containsKey("osImage")) {
            config.put("osImage", spec.osImage());
        }
        // root 디스크 크기 (GB). null/0 이하면 미주입 → Pulumi provider 가 model.defaults 의 기본(50GB) 적용.
        if (spec.rootDiskSizeGb() != null && spec.rootDiskSizeGb() > 0 && !config.containsKey("rootDiskSizeGb")) {
            config.put("rootDiskSizeGb", String.valueOf(spec.rootDiskSizeGb()));
        }
        // joinToken 은 backend 가 cluster 별 random 생성 — 사용자 입력은 항상 무시.
        // 약한/공유 token 입력을 허용하면 node 탈취 시 타 cluster join 가능. 생성 시점이
        // validateStatic 이전이므로 requestConfig snapshot 에 영속화 → retry 도 같은 token 재사용.
        if (config.containsKey(CONFIG_JOIN_TOKEN)) {
            log.warn("Ignoring user-supplied joinToken for cluster {} — backend generates a random one", clusterName);
        }
        config.put(CONFIG_JOIN_TOKEN, KubeadmJoinTokens.generate());
        dto.setConfig(config);
        return dto;
    }

    private String serialize(Object o) {
        try {
            return objectMapper.writeValueAsString(o);
        } catch (JsonProcessingException e) {
            return null;
        }
    }
}
