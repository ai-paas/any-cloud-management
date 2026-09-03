package com.aipaas.anycloud.domain.provisioning.bootstrap.support;

import com.aipaas.anycloud.domain.provisioning.model.VmClusterInternalRequestSnapshot;

public interface VmClusterBootstrapSnapshotService {

    VmClusterInternalRequestSnapshot read(String json);
}
