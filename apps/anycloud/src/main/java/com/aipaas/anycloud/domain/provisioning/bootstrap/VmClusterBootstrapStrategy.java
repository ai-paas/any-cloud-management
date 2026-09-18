package com.aipaas.anycloud.domain.provisioning.bootstrap;

import com.aipaas.anycloud.domain.provisioning.model.VmClusterInternalRequestSnapshot;

public interface VmClusterBootstrapStrategy {

    boolean supports(String provider);

    String waitForPreparationCommand();

    /**
     * kubeadm 명령을 돌리기 전에 노드를 준비시키는 명령.
     *
     * <p>대부분의 CSP 는 cloud-init 이 부팅 중에 패키지를 깔아 두므로 끝나기를 기다리기만 하면 된다.
     * cloud-init 으로 스크립트를 전달할 수 없는 CSP 는 이 자리에서 직접 설치한다.
     *
     * @param role {@code master} 또는 {@code worker} — 설치 목록이 갈린다
     */
    default String prepareNodeCommand(VmClusterInternalRequestSnapshot snapshot, String role) {
        return waitForPreparationCommand();
    }

    String initializeMasterCommand(VmClusterInternalRequestSnapshot snapshot);

    String resolveCaHashCommand();

    /** HA control-plane (MasterCount >= 2) 에서 extra master 들을 join 시킬 때 lead master 에 새 certificate-key 발급. {@code kubeadm init phase upload-certs --upload-certs} 출력의 마지막 줄 — caller 가 trim 후 buildControlPlaneJoinCommand 에 전달. */
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
