package com.aipaas.anycloud.domain.provisioning.bootstrap;

import com.aipaas.anycloud.domain.provisioning.VmClusterEntity;
import java.util.Map;

public interface VmClusterBootstrapService {

    void bootstrap(VmClusterEntity vmCluster, Map<String, Object> outputs);
}
