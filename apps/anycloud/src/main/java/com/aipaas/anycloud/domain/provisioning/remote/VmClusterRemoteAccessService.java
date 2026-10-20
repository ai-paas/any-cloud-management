package com.aipaas.anycloud.domain.provisioning.remote;

import com.aipaas.anycloud.domain.provisioning.VmClusterEntity;
import java.time.Duration;
import java.util.Map;

public interface VmClusterRemoteAccessService {

    /** NAT 뒤에서는 전체 노드가 동일 주소를 사용하고 포트로만 구분. host 만으로는 대상 식별 불가. */
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
