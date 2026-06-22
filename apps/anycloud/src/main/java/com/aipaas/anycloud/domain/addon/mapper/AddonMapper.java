package com.aipaas.anycloud.domain.addon.mapper;

import com.aipaas.anycloud.domain.addon.ClusterAddonEntity;
import com.aipaas.anycloud.domain.addon.api.response.AddonStatusResponse;
import com.aipaas.anycloud.domain.addon.model.AddonState;
import com.aipaas.anycloud.domain.cluster.model.ClusterStatus;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

/**
 * ClusterAddonEntity → AddonStatusResponse 매핑.
 *
 * <p>Entity 의 {@code addonType} 만 response 의 {@code type} 으로 rename — wire 호환성 (frontend
 * 가 {@code type} 으로 분기) 때문. 나머지 필드는 동일 이름이라 MapStruct 자동 매핑.
 *
 * <p>{@code pendingReason} 은 entity 에 없는 계산 필드 — auto-map 에서 ignore 하고,
 * cluster status 를 받는 overload 가 사유를 계산해 주입한다 (가시성 보장).
 */
@Mapper(componentModel = "spring")
public interface AddonMapper {

    @Mapping(target = "type", source = "addonType")
    @Mapping(target = "pendingReason", ignore = true)
    AddonStatusResponse toResponse(ClusterAddonEntity entity);

    /**
     * cluster status 를 받아 PENDING 정체 사유({@code pendingReason})를 계산해 주입.
     * enqueue 됐거나(state != PENDING) 이미 operation 이 붙은 addon 은 null.
     */
    default AddonStatusResponse toResponse(ClusterAddonEntity entity, ClusterStatus clusterStatus) {
        AddonStatusResponse r = toResponse(entity);
        String reason = pendingReason(entity, clusterStatus);
        if (reason == null) {
            return r;
        }
        // record 불변 — pendingReason 만 채운 복사본 재구성.
        return new AddonStatusResponse(
                r.id(),
                r.clusterId(),
                r.type(),
                r.catalogId(),
                r.releaseName(),
                r.namespace(),
                r.chartRepo(),
                r.chartName(),
                r.chartVersion(),
                r.repoUrl(),
                r.valuesYaml(),
                r.state(),
                r.lastOperationId(),
                r.lastError(),
                r.attempts(),
                r.enabled(),
                r.createdAt(),
                r.updatedAt(),
                reason);
    }

    /**
     * PENDING + lastOperationId=null (= 한 번도 enqueue 안 됨) 인 addon 에 대해 정체 사유 문자열 생성.
     * 그 외 상태는 null (정상 흐름이므로 노출 불필요).
     */
    static String pendingReason(ClusterAddonEntity entity, ClusterStatus clusterStatus) {
        if (entity.getState() != AddonState.PENDING || entity.getLastOperationId() != null) {
            return null;
        }
        String enqueueHint = " — POST /v1/clusters/" + entity.getClusterId() + "/addons:enqueue 로 즉시 enqueue 가능.";
        if (clusterStatus == ClusterStatus.ACTIVE) {
            return "cluster 가 ACTIVE 인데 아직 enqueue 안 됨 (생성 시점에 ACTIVE 가 아니었을 수 있음)" + enqueueHint;
        }
        return "cluster ACTIVE 또는 agent 세션 대기 중 (현재 status=" + clusterStatus + "). agent 등록·연결 " + "시 자동 enqueue"
                + enqueueHint;
    }
}
