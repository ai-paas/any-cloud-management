package com.aipaas.anycloud.domain.provisioning.bootstrap.support.internal;

import com.aipaas.anycloud.domain.provisioning.bootstrap.support.VmClusterNodeResolver;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

@Service
public class VmClusterNodeResolverImpl implements VmClusterNodeResolver {

    @Override
    public List<VmClusterNode> readNodes(Map<String, Object> outputs) {
        Object nodes = outputs.get("nodes");
        if (!(nodes instanceof List<?> values)) {
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
