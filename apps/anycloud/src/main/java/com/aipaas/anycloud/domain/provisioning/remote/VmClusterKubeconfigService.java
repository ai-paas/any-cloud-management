package com.aipaas.anycloud.domain.provisioning.remote;

import com.aipaas.anycloud.domain.provisioning.VmClusterEntity;
import java.util.Map;

public interface VmClusterKubeconfigService {

    String fetchKubeconfig(VmClusterEntity vmCluster, Map<String, Object> outputs);
}
