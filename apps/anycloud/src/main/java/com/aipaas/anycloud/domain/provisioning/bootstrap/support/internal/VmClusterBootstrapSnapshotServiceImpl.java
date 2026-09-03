package com.aipaas.anycloud.domain.provisioning.bootstrap.support.internal;

import com.aipaas.anycloud.domain.provisioning.bootstrap.support.VmClusterBootstrapSnapshotService;
import com.aipaas.anycloud.domain.provisioning.model.VmClusterInternalRequestSnapshot;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class VmClusterBootstrapSnapshotServiceImpl implements VmClusterBootstrapSnapshotService {

    private final ObjectMapper objectMapper;

    @Override
    public VmClusterInternalRequestSnapshot read(String json) {
        if (json == null || json.isBlank()) {
            return VmClusterInternalRequestSnapshot.builder().build();
        }
        try {
            return objectMapper.readValue(json, VmClusterInternalRequestSnapshot.class);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to parse VM cluster request snapshot", e);
        }
    }
}
