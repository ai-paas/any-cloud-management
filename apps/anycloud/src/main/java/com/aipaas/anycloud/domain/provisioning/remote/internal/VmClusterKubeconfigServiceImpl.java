package com.aipaas.anycloud.domain.provisioning.remote.internal;

import com.aipaas.anycloud.domain.provisioning.VmClusterEntity;
import com.aipaas.anycloud.domain.provisioning.remote.VmClusterKubeconfigService;
import com.aipaas.anycloud.domain.provisioning.remote.VmClusterRemoteAccessService;
import java.time.Duration;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class VmClusterKubeconfigServiceImpl implements VmClusterKubeconfigService {

    private final VmClusterRemoteAccessService vmClusterRemoteAccessService;

    @Override
    public String fetchKubeconfig(VmClusterEntity vmCluster, Map<String, Object> outputs) {
        try {
            return fetch(vmCluster, outputs);
        } catch (Exception e) {
            throw new IllegalStateException(
                    "Failed to fetch kubeconfig for VM cluster " + vmCluster.getClusterName(), e);
        }
    }

    private String fetch(VmClusterEntity vmCluster, Map<String, Object> outputs) throws Exception {
        String host = firstNonBlank(
                stringValue(outputs.get("masterPublicDns")),
                stringValue(outputs.get("masterPublicIp")),
                stringValue(outputs.get("masterPrivateIp")));
        String remotePath =
                firstNonBlank(stringValue(outputs.get("kubeconfigRemotePath")), "/etc/kubernetes/admin.conf");

        if (host == null || host.isBlank()) {
            throw new IllegalStateException("Missing master host in Pulumi outputs");
        }

        return vmClusterRemoteAccessService.readSudoFileOnMaster(vmCluster, outputs, remotePath, Duration.ofMinutes(2));
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
