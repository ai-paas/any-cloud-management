package io.aipaas.cluster.provisioning.program.provisioner;

import com.pulumi.core.Output;
import com.pulumi.tls.PrivateKey;
import java.util.List;
import java.util.Map;

/**
 * Provisioner 가 생성한 cluster resource 의 정규화 결과. {@link AbstractKubeadmProvisioner} 가
 * 본 record 를 받아 표준 output map 으로 변환.
 *
 * @param sshKey TLS keypair — sshPrivateKeyPem export 에 사용.
 * @param master master 노드 결과.
 * @param workers worker 노드 결과 목록 (workerCount = 0 시 빈 리스트).
 * @param vpcId VPC/Network ID — vpcId output 으로 export.
 * @param extras CSP-specific 추가 output (dbEndpoint / osImage override 등). 없으면 빈 맵.
 */
public record ProvisionedCluster(
        PrivateKey sshKey,
        InstanceOutput master,
        List<InstanceOutput> workers,
        Output<String> vpcId,
        Map<String, Output<?>> extras) {

    public ProvisionedCluster {
        if (master == null) {
            throw new IllegalArgumentException("ProvisionedCluster: master is required");
        }
        if (workers == null) {
            throw new IllegalArgumentException("ProvisionedCluster: workers list cannot be null (empty OK)");
        }
        if (extras == null) {
            extras = Map.of();
        }
    }
}
