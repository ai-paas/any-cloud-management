package com.aipaas.anycloud.domain.provisioning.internal;

import com.aipaas.anycloud.domain.provisioning.VmClusterStateHistoryEntity;
import com.aipaas.anycloud.domain.provisioning.VmClusterStateHistoryQueryService;
import com.aipaas.anycloud.domain.provisioning.VmClusterStateHistoryRepository;
import com.aipaas.anycloud.domain.provisioning.mapper.VmClusterStateHistoryMapper;
import com.aipaas.anycloud.domain.provisioning.model.VmClusterStateHistory;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * {@link VmClusterStateHistoryQueryService} impl. paged repository 위임.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class VmClusterStateHistoryQueryServiceImpl implements VmClusterStateHistoryQueryService {

    private final VmClusterStateHistoryRepository repository;
    private final VmClusterStateHistoryMapper stateHistoryMapper;

    @Override
    public List<VmClusterStateHistoryEntity> listRecent(String clusterName, int pageSize) {
        return repository.findByClusterNameOrderByCreatedAtDesc(clusterName, PageRequest.of(0, pageSize));
    }

    @Override
    public List<VmClusterStateHistory> listRecentDomain(String clusterName, int pageSize) {
        return listRecent(clusterName, pageSize).stream()
                .map(stateHistoryMapper::toDomain)
                .toList();
    }
}
