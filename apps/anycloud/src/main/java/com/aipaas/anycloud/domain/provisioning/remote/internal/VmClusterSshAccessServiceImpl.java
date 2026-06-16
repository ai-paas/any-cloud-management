package com.aipaas.anycloud.domain.provisioning.remote.internal;

import com.aipaas.anycloud.common.error.exception.ClusterNotFoundException;
import com.aipaas.anycloud.domain.provisioning.VmClusterEntity;
import com.aipaas.anycloud.domain.provisioning.VmClusterRepository;
import com.aipaas.anycloud.domain.provisioning.properties.PulumiProperties;
import com.aipaas.anycloud.domain.provisioning.remote.VmClusterSshAccessService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.aipaas.cluster.provisioning.service.PulumiProvisioningService;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
public class VmClusterSshAccessServiceImpl implements VmClusterSshAccessService {

    private final VmClusterRepository vmClusterRepository;
    private final PulumiProvisioningService pulumiProvisioningService;
    private final PulumiProperties pulumiProperties;
    private final ObjectMapper objectMapper;

    @Override
    @Transactional(readOnly = true)
    public NodeListResult listNodes(String clusterName) {
        VmClusterEntity vmCluster = requireCluster(clusterName);
        List<Map<String, Object>> nodes = parseNodesFromRawOutputs(vmCluster.getRawOutputs());
        if (nodes.isEmpty()) {
            throw new VmClusterSshAccessException(
                    "OUTPUTS_NOT_READY",
                    "No node outputs yet — cluster has not finished PROVISION (status="
                            + vmCluster.getProvisioningStatus() + ")");
        }
        return new NodeListResult(clusterName, vmCluster.getClusterProvider(), pulumiProperties.getSshUser(), nodes);
    }

    /**
     * Pulumi CLI 호출 (수 초) — caller TX 점유 방지 위해 NOT_SUPPORTED.
     */
    @Override
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public SshKeyResult issueSshKey(String clusterName) {
        VmClusterEntity vmCluster = requireCluster(clusterName);
        if (vmCluster.getStackName() == null || vmCluster.getStackName().isBlank()) {
            throw new VmClusterSshAccessException("NO_STACK", "Cluster has no Pulumi stack: " + clusterName);
        }
        Map<String, Object> outputs;
        try {
            outputs = pulumiProvisioningService.stackOutputs(vmCluster.getStackName(), true, Map.of());
        } catch (IllegalStateException e) {
            throw new VmClusterSshAccessException(
                    "STACK_READ_FAILED", "Failed to read Pulumi stack outputs: " + e.getMessage());
        }
        String pem = stringValue(outputs.get("sshPrivateKeyPem"));
        if (pem == null || pem.isBlank()) {
            throw new VmClusterSshAccessException(
                    "KEY_NOT_FOUND", "sshPrivateKeyPem missing from stack outputs of " + vmCluster.getStackName());
        }
        String sshUser = pulumiProperties.getSshUser();
        List<SshKeyResult.NodeSshInfo> nodes = new ArrayList<>();
        for (Map<String, Object> node : nodesFromOutputs(outputs)) {
            String publicIp = stringValue(node.get("publicIp"));
            String privateIp = stringValue(node.get("privateIp"));
            String role = stringValue(node.get("role"));
            String target = publicIp != null && !publicIp.isBlank() ? publicIp : privateIp;
            String sshCommand = target == null ? null : "ssh -i " + clusterName + "-ssh.pem " + sshUser + "@" + target;
            nodes.add(new SshKeyResult.NodeSshInfo(role, publicIp, privateIp, sshCommand));
        }
        log.info("Issued SSH private key for cluster {} ({} nodes)", clusterName, nodes.size());
        return new SshKeyResult(clusterName, sshUser, pem, nodes);
    }

    private VmClusterEntity requireCluster(String clusterName) {
        return vmClusterRepository
                .findFirstByClusterNameOrderByCreatedAtDesc(clusterName)
                .orElseThrow(() -> new ClusterNotFoundException(clusterName));
    }

    private List<Map<String, Object>> parseNodesFromRawOutputs(String rawOutputs) {
        if (rawOutputs == null || rawOutputs.isBlank()) {
            return List.of();
        }
        try {
            JsonNode root = objectMapper.readTree(rawOutputs);
            JsonNode nodes = root.path("nodes");
            List<Map<String, Object>> result = new ArrayList<>();
            for (JsonNode node : nodes) {
                result.add(objectMapper.convertValue(node, LinkedHashMap.class));
            }
            return result;
        } catch (Exception e) {
            log.warn("Failed to parse rawOutputs nodes: {}", e.toString());
            return List.of();
        }
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> nodesFromOutputs(Map<String, Object> outputs) {
        Object nodes = outputs.get("nodes");
        if (!(nodes instanceof List<?> list)) {
            return List.of();
        }
        List<Map<String, Object>> result = new ArrayList<>();
        for (Object item : list) {
            if (item instanceof Map<?, ?> m) {
                result.add((Map<String, Object>) m);
            }
        }
        return result;
    }

    private static String stringValue(Object value) {
        return value == null ? null : String.valueOf(value);
    }
}
