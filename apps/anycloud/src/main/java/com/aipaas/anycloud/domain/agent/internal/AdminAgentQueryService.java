package com.aipaas.anycloud.domain.agent.internal;

import com.aipaas.anycloud.domain.agent.ClusterAgentEntity;
import com.aipaas.anycloud.domain.agent.ClusterAgentRepository;
import com.aipaas.anycloud.domain.agent.api.response.AdminAgentListResponse;
import com.aipaas.anycloud.domain.agent.model.ClusterAgentStatus;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;

/**
 * Fleet 페이지 read-only query — filter → DB → DTO.
 * size 상한 200 강제 (UI excessive page 방지).
 */
@Service
@RequiredArgsConstructor
public class AdminAgentQueryService {

    private static final int MAX_SIZE = 200;

    private final ClusterAgentRepository repository;

    public AdminAgentListResponse query(
            List<ClusterAgentStatus> statuses,
            List<String> clusterNames,
            String versionPrefix,
            Long lastSeenOlderThanSec,
            int page,
            int size) {
        int safeSize = Math.min(Math.max(size, 1), MAX_SIZE);
        int safePage = Math.max(page, 0);
        LocalDateTime now = LocalDateTime.now();
        var spec = AdminAgentSpecs.combine(statuses, clusterNames, versionPrefix, lastSeenOlderThanSec, now);
        Page<ClusterAgentEntity> result = repository.findAll(
                spec,
                PageRequest.of(safePage, safeSize, Sort.by(Sort.Direction.ASC, "clusterName", "agentInstanceId")));
        List<AdminAgentListResponse.Item> items =
                result.getContent().stream().map(e -> toItem(e, now)).toList();
        return new AdminAgentListResponse(
                items, (int) result.getTotalElements(), result.getNumber(), result.getSize(), result.getTotalPages());
    }

    private AdminAgentListResponse.Item toItem(ClusterAgentEntity e, LocalDateTime now) {
        LocalDateTime lastSeen = e.getLastSeenAt();
        Long ageSec = lastSeen == null ? null : Duration.between(lastSeen, now).getSeconds();
        return new AdminAgentListResponse.Item(
                e.getAgentId(),
                e.getClusterName(),
                e.getAgentInstanceId(),
                e.getStatus() == null ? null : e.getStatus().name(),
                e.getAgentVersion(),
                lastSeen,
                ageSec,
                e.getLastError());
    }
}
