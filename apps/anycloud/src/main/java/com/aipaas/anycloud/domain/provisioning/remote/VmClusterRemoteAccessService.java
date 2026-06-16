package com.aipaas.anycloud.domain.provisioning.remote;

import com.aipaas.anycloud.domain.provisioning.VmClusterEntity;
import java.time.Duration;
import java.util.Map;

public interface VmClusterRemoteAccessService {

    String runOnHost(
            VmClusterEntity vmCluster, Map<String, Object> outputs, String host, String command, Duration timeout);

    String runOnMaster(VmClusterEntity vmCluster, Map<String, Object> outputs, String command, Duration timeout);

    String readSudoFileOnMaster(
            VmClusterEntity vmCluster, Map<String, Object> outputs, String remotePath, Duration timeout);
}
