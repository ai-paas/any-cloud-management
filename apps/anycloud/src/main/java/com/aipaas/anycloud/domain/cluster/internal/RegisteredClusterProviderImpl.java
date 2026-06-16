package com.aipaas.anycloud.domain.cluster.internal;

import com.aipaas.anycloud.domain.cluster.ClusterProvider;
import com.aipaas.anycloud.domain.cluster.ClusterService;
import com.aipaas.anycloud.domain.cluster.api.request.CreateClusterDto;
import com.aipaas.anycloud.domain.cluster.api.request.CreateClusterRequest;
import com.aipaas.anycloud.domain.cluster.mapper.ClusterSpecMapper;
import com.aipaas.anycloud.domain.cluster.model.RegisteredClusterSpec;
import com.aipaas.anycloud.domain.operation.OperationEntity;
import com.aipaas.anycloud.domain.operation.OperationService;
import com.aipaas.anycloud.domain.operation.model.OperationType;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Registered source cluster 등록 strategy.
 * <p>
 * VM 과 달리 외부 cluster 등록은 동기 — Pulumi / Bootstrap workflow 없이 즉시 DB 등록 후 완료.
 * spec 은 {@link RegisteredClusterSpec} record 로 typed 처리.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RegisteredClusterProviderImpl implements ClusterProvider {

    private final ClusterService clusterService;
    private final OperationService operationService;
    private final ObjectMapper objectMapper;
    // AgentApiManagedInstaller / AgentProperties 의존 제거 — auto-install
    // 흐름 삭제. install 은 사용자가 응답에 박힌 helm command 로 직접 실행.

    @Override
    public String source() {
        return "registered";
    }

    @Override
    public OperationEntity create(CreateClusterRequest request) {
        RegisteredClusterSpec spec = ClusterSpecMapper.toRegistered(request.getSpec());

        String resourcePayload = serialize(request);
        OperationEntity op = operationService.start(
                OperationType.CREATE_CLUSTER, "cluster", request.getClusterName(), resourcePayload, 1);
        try {
            CreateClusterDto dto = toCreateClusterDto(request.getClusterName(), spec);
            clusterService.createCluster(dto);

            // K8s API 직접 호출하던 auto-install 흐름 제거.
            // 사용자가 응답에 박힌 bootstrap.helmInstallCommand 또는 agent-manifest.yaml 로
            // cluster-agent 를 직접 install. install 후 agent dial-in 시 ACTIVE 전환.

            operationService.complete(op.getId(), null); // sync 완료 (DB 등록만)
        } catch (Exception e) {
            operationService.fail(op.getId(), e.getMessage());
            throw e;
        }
        return op;
    }

    private CreateClusterDto toCreateClusterDto(String clusterName, RegisteredClusterSpec spec) {
        CreateClusterDto dto = new CreateClusterDto();
        dto.setClusterName(clusterName);
        dto.setClusterType(spec.clusterType());
        dto.setClusterProvider(spec.provider());
        dto.setDescription(spec.description());
        // apiServerUrl/IP, serverCA, clientCA/Key/Token, monitServerURL 제거.
        dto.setHasGpuNodes(spec.hasGpuNodes());
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
