package com.aipaas.anycloud.domain.provisioning.query;

import com.aipaas.anycloud.domain.provisioning.VmClusterEntity;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Pulumi outputs 의 nodes 배열을 목록에 세울 수 있는 행으로 바꾼다.
 *
 * <p>노드는 자기 테이블이 없다. outputs 에는 이름도 상태도 없어서 이름은 역할과 순서로 만들고,
 * 상태는 클러스터에서 물려받는다. 실시간 쿠버네티스 상태는 agent 를 거쳐야 해서 여기 없다.
 */
public final class VmClusterNodeRows {

    private VmClusterNodeRows() {}

    private static final int DEFAULT_SSH_PORT = 22;

    public record Row(
            String nodeName,
            String role,
            String instanceId,
            String privateIp,
            String publicIp,
            String publicDns,
            String clusterName,
            String clusterProvider,
            String region,
            String environment,
            String infraStatus,
            /* NAT 뒤 노드는 공유기가 노드마다 다른 포트를 연다. 예전 스택에는 없어 22 로 떨어진다. */
            int sshPort,
            /** 노드가 아직 없어 클러스터를 대신 세운 줄. */
            boolean pending) {}

    /**
     * 목록에 세울 대표값.
     *
     * @param masterPrivateIp 첫 master 의 주소. HA 면 나머지는 상세에서 본다
     */
    public record Summary(int masterCount, int workerCount, String masterPrivateIp, String masterPublicIp) {}

    public static Summary summarize(List<Row> rows) {
        int masters = 0;
        int workers = 0;
        String privateIp = null;
        String publicIp = null;
        for (Row row : rows) {
            if (isMaster(row)) {
                if (masters == 0) {
                    privateIp = row.privateIp();
                    publicIp = row.publicIp();
                }
                masters++;
            } else {
                workers++;
            }
        }
        return new Summary(masters, workers, privateIp, publicIp);
    }

    private static boolean isMaster(Row row) {
        return row.role() != null && "master".equalsIgnoreCase(row.role());
    }

    public static List<Row> of(ObjectMapper mapper, VmClusterEntity cluster) {
        List<JsonNode> nodes = readNodes(mapper, cluster.getRawOutputs());
        String status = cluster.getProvisioningStatus() == null
                ? null
                : cluster.getProvisioningStatus().name();
        if (nodes.isEmpty()) {
            /*
             * 노드는 PROVISION 이 끝나야 생긴다. 빈 목록을 돌려주면 만들어지는 중이거나 실패한
             * 클러스터가 목록에서 통째로 사라진다. 화면이 대신 끼워 넣던 줄을 여기서 만든다 —
             * 자르는 곳과 세는 곳이 갈리면 페이지마다 전체 개수가 달라진다.
             */
            return List.of(new Row(
                    cluster.getClusterName(),
                    null,
                    null,
                    null,
                    null,
                    null,
                    cluster.getClusterName(),
                    cluster.getClusterProvider(),
                    cluster.getRegion(),
                    cluster.getEnvironment(),
                    status,
                    DEFAULT_SSH_PORT,
                    true));
        }
        Map<String, Integer> seenPerRole = new HashMap<>();
        List<Row> rows = new ArrayList<>(nodes.size());
        for (JsonNode node : nodes) {
            String role = text(node, "role");
            String roleKey = role == null ? "node" : role.toLowerCase(Locale.ROOT);
            int index = seenPerRole.merge(roleKey, 0, (prev, ignored) -> prev + 1);
            rows.add(new Row(
                    cluster.getClusterName() + "-" + roleKey + "-" + index,
                    role,
                    text(node, "instanceId"),
                    text(node, "privateIp"),
                    text(node, "publicIp"),
                    text(node, "publicDns"),
                    cluster.getClusterName(),
                    cluster.getClusterProvider(),
                    cluster.getRegion(),
                    cluster.getEnvironment(),
                    status,
                    node.path("sshPort").asInt(DEFAULT_SSH_PORT),
                    false));
        }
        return rows;
    }

    /**
     * YAML 프로그램은 nodes 를 JSON 문자열로 내보낸다 — 배열을 그대로 내보내면 SDK 가 죽어서다.
     * 배열과 문자열을 모두 받는다.
     *
     * <p>깨진 outputs 는 빈 목록이다. 한 클러스터 때문에 전체 목록이 실패하면 안 된다.
     */
    private static List<JsonNode> readNodes(ObjectMapper mapper, String rawOutputs) {
        if (rawOutputs == null || rawOutputs.isBlank()) {
            return List.of();
        }
        try {
            JsonNode nodes = mapper.readTree(rawOutputs).path("nodes");
            if (nodes.isTextual()) {
                nodes = mapper.readTree(nodes.asText());
            }
            if (!nodes.isArray()) {
                return List.of();
            }
            List<JsonNode> result = new ArrayList<>(nodes.size());
            nodes.forEach(result::add);
            return result;
        } catch (Exception e) {
            return List.of();
        }
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isMissingNode() || value.isNull() ? null : value.asText();
    }
}
