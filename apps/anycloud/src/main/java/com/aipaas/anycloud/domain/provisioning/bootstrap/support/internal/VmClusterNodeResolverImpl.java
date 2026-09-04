package com.aipaas.anycloud.domain.provisioning.bootstrap.support.internal;

import com.aipaas.anycloud.domain.provisioning.bootstrap.support.VmClusterNodeResolver;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Slf4j
@Service
public class VmClusterNodeResolverImpl implements VmClusterNodeResolver {

    private static final ObjectMapper NODES_JSON = new ObjectMapper();
    private static final TypeReference<List<Map<String, Object>>> NODES_TYPE = new TypeReference<>() {};

    @Override
    public List<VmClusterNode> readNodes(Map<String, Object> outputs) {
        List<?> values = toNodeList(outputs.get("nodes"));
        if (values.isEmpty()) {
            return List.of();
        }

        return values.stream()
                .filter(Map.class::isInstance)
                .map(Map.class::cast)
                .map(node -> new VmClusterNode(
                        stringValue(node.get("role")),
                        firstNonBlank(stringValue(node.get("publicDns")), stringValue(node.get("publicIp")))))
                .filter(node -> node.host() != null && !node.host().isBlank())
                .toList();
    }

    /**
     * nodes 는 provisioner 에 따라 배열로도 JSON 문자열로도 온다.
     *
     * <p>Pulumi Java SDK 의 일부 역직렬화 경로가 배열 값을 못 다뤄 provisioner 가 문자열로 export
     * 한다. 문자열을 못 읽으면 worker join 목록이 비고 — 그 경로에는 폴백이 없다 — worker 가
     * 클러스터에 붙지 않는다.
     */
    private List<?> toNodeList(Object value) {
        if (value instanceof List<?> list) {
            return list;
        }
        if (value instanceof String text && !text.isBlank()) {
            try {
                return NODES_JSON.readValue(text, NODES_TYPE);
            } catch (Exception e) {
                log.warn("nodes JSON 문자열 파싱 실패: {}", e.getMessage());
            }
        }
        return List.of();
    }

    @Override
    public String masterHost(Map<String, Object> outputs) {
        return readNodes(outputs).stream()
                .filter(node -> "master".equalsIgnoreCase(node.role()))
                .map(VmClusterNode::host)
                .filter(host -> host != null && !host.isBlank())
                .findFirst()
                .orElse(firstNonBlank(
                        stringValue(outputs.get("masterPublicDns")), stringValue(outputs.get("masterPublicIp"))));
    }

    @Override
    public String masterPrivateIp(Map<String, Object> outputs) {
        return stringValue(outputs.get("masterPrivateIp"));
    }

    private String stringValue(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }
}
