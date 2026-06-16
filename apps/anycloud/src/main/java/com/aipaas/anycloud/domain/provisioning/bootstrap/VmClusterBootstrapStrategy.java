package com.aipaas.anycloud.domain.provisioning.bootstrap;

import com.aipaas.anycloud.domain.provisioning.model.VmClusterInternalRequestSnapshot;

public interface VmClusterBootstrapStrategy {

    boolean supports(String provider);

    String waitForPreparationCommand();

    String initializeMasterCommand(VmClusterInternalRequestSnapshot snapshot);

    String resolveCaHashCommand();

    /**
     * HA control-plane (MasterCount >= 2) 에서 extra master 들을 join 시킬 때 lead master 에
     * 새 certificate-key 발급. {@code kubeadm init phase upload-certs --upload-certs} 출력의
     * 마지막 줄 — caller 가 trim 후 buildControlPlaneJoinCommand 에 전달.
     */
    default String uploadCertsCommand() {
        return "sudo kubeadm init phase upload-certs --upload-certs 2>/dev/null | tail -n 1";
    }

    /**
     * Extra control-plane 노드의 join 명령. lead master IP / token / CA hash / cert key 가 필요.
     * single master 만 지원하는 strategy 라면 UnsupportedOperationException 던져 caller 가 skip.
     */
    default String buildControlPlaneJoinCommand(
            VmClusterInternalRequestSnapshot snapshot,
            String leadMasterPrivateIp,
            String caHash,
            String certificateKey) {
        throw new UnsupportedOperationException("Control-plane join not supported by "
                + getClass().getSimpleName() + ". MasterCount must be 1 for this strategy.");
    }

    String buildWorkerJoinCommand(VmClusterInternalRequestSnapshot snapshot, String masterPrivateIp, String caHash);

    String waitForClusterReadyCommand();

    String buildAddonInstallCommand(VmClusterInternalRequestSnapshot snapshot);
}
