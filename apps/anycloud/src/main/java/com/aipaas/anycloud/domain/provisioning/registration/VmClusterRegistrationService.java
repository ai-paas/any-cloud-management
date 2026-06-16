package com.aipaas.anycloud.domain.provisioning.registration;

import com.aipaas.anycloud.domain.cluster.ClusterEntity;
import com.aipaas.anycloud.domain.provisioning.VmClusterEntity;

public interface VmClusterRegistrationService {

    ClusterEntity registerFromKubeconfig(VmClusterEntity vmCluster, String kubeconfigContent);
}
