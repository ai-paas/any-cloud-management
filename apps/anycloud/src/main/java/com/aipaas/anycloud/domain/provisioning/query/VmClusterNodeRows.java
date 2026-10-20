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
 * Pulumi outputs 의 nodes 배열을 목록 표시용 행으로 변환.
 *
 * <p>노드 전용 테이블 부재. outputs 에 이름과 상태가 없어 이름은 역할과 순서로 생성하고 상태는
 * 클러스터 값 상속. 실시간 쿠버네티스 상태는 agent 경유이므로 대상 외.
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
            /* NAT 뒤 노드는 공유기가 노드별로 다른 포트 개방. 이전 스택에는 값이 없어 22 로 대체. */
            int sshPort,
            /** 노드가 아직 없어 클러스터를 대신 세운 줄. */
            boolean pending) {}

    /**
     * 목록에 세울 대표값.
     *
     * @param masterPrivateIp 첫 master 의 주소. HA 구성의 나머지 노드는 상세 조회에서 제공
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
             * 노드는 PROVISION 완료 후 생성. 빈 목록 반환 시 생성 중이거나 실패한 클러스터가
             * 목록에서 누락. 화면이 보완하던 행을 여기서 생성. 자르는 지점과 세는 지점이
             * 분리되면 페이지별 전체 개수 불일치.
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
     * YAML 프로그램은 nodes 를 JSON 문자열로 출력. 배열을 그대로 출력하면 SDK 실패. 배열과
     * 문자열 모두 수용.
     *
     * <p>손상된 outputs 는 빈 목록 처리. 클러스터 하나로 전체 목록 조회를 실패시키지 않음.
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
