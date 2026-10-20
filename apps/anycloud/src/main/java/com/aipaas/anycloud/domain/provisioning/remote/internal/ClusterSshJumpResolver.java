package com.aipaas.anycloud.domain.provisioning.remote.internal;

import com.aipaas.anycloud.domain.credential.CspCredentialService;
import com.aipaas.anycloud.domain.provisioning.VmClusterEntity;
import com.aipaas.anycloud.domain.provisioning.remote.SshJump;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 해당 클러스터 생성에 사용한 자격증명에서 점프 호스트 조회.
 *
 * <p>점프 호스트는 클라우드별 상이. OpenStack 은 설치별 bastion 이 다르고 AWS, OCI 는 공인 IP 라
 * 불필요. 백엔드 전역 설정으로는 표현 불가.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ClusterSshJumpResolver {

    private final CspCredentialService cspCredentialService;

    /** 자격증명이 사라졌거나 읽히지 않으면 점프 없이 간다: 여기서 막으면 직접 닿는 환경까지 멈춘다. */
    public SshJump resolve(VmClusterEntity cluster) {
        if (cluster == null
                || cluster.getCredentialId() == null
                || cluster.getCredentialId().isBlank()) {
            return null;
        }
        try {
            return SshJump.from(
                    cspCredentialService.resolveEnvironment(cluster.getClusterProvider(), cluster.getCredentialId()));
        } catch (Exception e) {
            log.debug("점프 호스트 설정을 읽지 못했다 cluster={}: {}", cluster.getClusterName(), e.toString());
            return null;
        }
    }
}
