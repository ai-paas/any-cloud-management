package com.aipaas.anycloud.domain.provisioning.bootstrap.providers;

import com.aipaas.anycloud.domain.provisioning.model.VmClusterInternalRequestSnapshot;
import io.aipaas.cluster.provisioning.program.KubeadmUserData;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * Proxmox 노드는 cloud-init 으로 패키지를 받지 못해 여기서 직접 설치한다.
 *
 * <p>Proxmox API 는 업로드 content type 으로 snippets 를 받지 않는다(pve-devel #2208, 미해결).
 * user-data 를 넣으려면 provider 가 PVE 호스트에 SSH 로 들어가 파일을 밀어 넣어야 하는데, 그러려면
 * 하이퍼바이저 호스트 접근 권한을 프로비저닝에 내줘야 한다. 같은 스크립트를 노드 SSH 로 실행하면
 * 그 권한이 필요 없다.
 */
@Component
@Order(90)
public class ProxmoxVmClusterBootstrapStrategy extends GenericLinuxVmClusterBootstrapStrategy {

    @Override
    public boolean supports(String provider) {
        return "Proxmox".equalsIgnoreCase(provider);
    }

    @Override
    public String prepareNodeCommand(VmClusterInternalRequestSnapshot snapshot, String role) {
        String script = KubeadmUserData.forRole(kubernetesVersion(snapshot), role);
        /*
         * 스크립트를 base64 로 실어 보낸다. heredoc 은 따옴표와 $ 가 섞인 본문에서 셸이 한 번 더
         * 해석해 내용이 바뀐다. 실행 후 준비 상태는 공용 대기 명령으로 확인한다.
         */
        String encoded = Base64.getEncoder().encodeToString(script.getBytes(StandardCharsets.UTF_8));
        return "echo " + encoded + " | base64 -d | sudo bash -s; " + waitForPreparationCommand();
    }

    /** 스냅샷에 버전이 없으면 emitter 기본값과 같은 값을 쓴다. 다르면 노드마다 다른 버전이 깔린다. */
    private String kubernetesVersion(VmClusterInternalRequestSnapshot snapshot) {
        String version = snapshot == null ? null : snapshot.getKubernetesVersion();
        return version == null || version.isBlank() ? DEFAULT_K8S_VERSION : version;
    }
}
