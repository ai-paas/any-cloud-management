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
        return extraMasterNodes(outputs).stream().map(VmClusterNode::host).toList();
    }

    /**
     * 노드가 22 가 아닌 포트로 열리는 경우가 있다. NAT 뒤 홈랩은 공유기가 노드마다 다른 포트를
     * 바깥쪽에 연다.
     */
    record VmClusterNode(String role, String host, int port) {

        public VmClusterNode(String role, String host) {
            this(role, host, DEFAULT_SSH_PORT);
        }
    }

    int DEFAULT_SSH_PORT = 22;

    /** host 만 보유한 호출자용. 동일 주소 노드가 여럿이면 첫 번째 반환. */
    default int sshPortOf(Map<String, Object> outputs, String host) {
        return readNodes(outputs).stream()
                .filter(node -> host != null && host.equals(node.host()))
                .mapToInt(VmClusterNode::port)
                .findFirst()
                .orElse(DEFAULT_SSH_PORT);
    }

    /**
     * lead master 접속 정보.
     *
     * <p>host 만으로는 노드 구분 불가. NAT 뒤에서는 전체 노드가 동일 공인 주소를 사용하고
     * 포트로만 구분.
     */
    default VmClusterNode masterNode(Map<String, Object> outputs) {
        String host = masterHost(outputs);
        return readNodes(outputs).stream()
                .filter(node -> "master".equalsIgnoreCase(node.role()))
                .findFirst()
                .orElse(new VmClusterNode("master", host));
    }

    /** lead master 를 뺀 나머지 control-plane. 순서는 outputs.nodes 선언 순서다. */
    default List<VmClusterNode> extraMasterNodes(Map<String, Object> outputs) {
        List<VmClusterNode> masters = readNodes(outputs).stream()
                .filter(node -> "master".equalsIgnoreCase(node.role()))
                .toList();
        return masters.isEmpty() ? List.of() : masters.subList(1, masters.size());
    }
}
