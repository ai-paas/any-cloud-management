package com.aipaas.anycloud.domain.provisioning.bootstrap.providers;

import com.aipaas.anycloud.domain.provisioning.model.VmClusterInternalRequestSnapshot;
import io.aipaas.cluster.provisioning.program.KubeadmUserData;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * Proxmox 노드는 cloud-init 으로 패키지 수신이 불가해 이 지점에서 직접 설치.
 *
 * <p>Proxmox API 는 업로드 content type 으로 snippets 미지원(pve-devel #2208, 미해결).
 * user-data 주입에는 provider 의 PVE 호스트 SSH 접근이 필요하며, 이는 하이퍼바이저 호스트
 * 접근 권한을 프로비저닝에 부여하는 것. 동일 스크립트를 노드 SSH 로 실행하면 해당 권한 불필요.
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
         * 스크립트를 base64 로 전달. heredoc 은 따옴표와 $ 가 포함된 본문에서 셸이 추가 해석해
         * 내용 변형. 실행 후 준비 상태는 공용 대기 명령으로 확인.
         */
        String encoded = Base64.getEncoder().encodeToString(script.getBytes(StandardCharsets.UTF_8));
        return "echo " + encoded + " | base64 -d | sudo bash -s; " + waitForPreparationCommand();
    }

    /** 스냅샷에 버전 부재 시 emitter 기본값과 동일 값 사용. 불일치 시 노드별 버전 상이. */
    private String kubernetesVersion(VmClusterInternalRequestSnapshot snapshot) {
        String version = snapshot == null ? null : snapshot.getKubernetesVersion();
        return version == null || version.isBlank() ? DEFAULT_K8S_VERSION : version;
    }
}
