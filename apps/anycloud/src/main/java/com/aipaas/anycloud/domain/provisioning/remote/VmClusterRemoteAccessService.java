package com.aipaas.anycloud.domain.provisioning.remote;

import com.aipaas.anycloud.domain.provisioning.VmClusterEntity;
import java.time.Duration;
import java.util.Map;

public interface VmClusterRemoteAccessService {

    /** NAT 뒤에서는 노드가 모두 같은 주소를 쓰고 포트로만 갈린다. host 만으로는 대상을 특정하지 못한다. */
    String runOnHost(
            VmClusterEntity vmCluster,
            Map<String, Object> outputs,
            String host,
            int port,
            String command,
            Duration timeout);

    String runOnHost(
            VmClusterEntity vmCluster, Map<String, Object> outputs, String host, String command, Duration timeout);

    String runOnMaster(VmClusterEntity vmCluster, Map<String, Object> outputs, String command, Duration timeout);

    String readSudoFileOnMaster(
            VmClusterEntity vmCluster, Map<String, Object> outputs, String remotePath, Duration timeout);
}
