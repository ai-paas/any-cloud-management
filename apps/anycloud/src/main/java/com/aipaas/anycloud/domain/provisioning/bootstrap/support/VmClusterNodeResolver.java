package com.aipaas.anycloud.domain.provisioning.bootstrap.support;

import java.util.List;
import java.util.Map;

public interface VmClusterNodeResolver {

    List<VmClusterNode> readNodes(Map<String, Object> outputs);

    String masterHost(Map<String, Object> outputs);

    String masterPrivateIp(Map<String, Object> outputs);

    /**
     * HA control-plane 의 extra master host 목록 (lead master = master-1 제외).
     * single-master cluster 면 빈 리스트. order 는 outputs.nodes 의 declaration 순서.
     */
    default List<String> extraMasterHosts(Map<String, Object> outputs) {
        List<VmClusterNode> all = readNodes(outputs);
        String lead = masterHost(outputs);
        return all.stream()
                .filter(node -> "master".equalsIgnoreCase(node.role()))
                .map(VmClusterNode::host)
                .filter(host -> host != null && !host.isBlank() && !host.equals(lead))
                .toList();
    }

    record VmClusterNode(String role, String host) {}
}
