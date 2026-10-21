package com.aipaas.anycloud.domain.provisioning.remote.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.aipaas.anycloud.domain.provisioning.VmClusterEntity;
import com.aipaas.anycloud.domain.provisioning.VmClusterRepository;
import com.aipaas.anycloud.domain.provisioning.properties.PulumiProperties;
import com.aipaas.anycloud.domain.provisioning.remote.VmClusterSshAccessService.SshKeyResult;
import com.aipaas.anycloud.testsupport.AbstractUnitTest;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.aipaas.cluster.provisioning.api.ProvisioningService;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * NAT 뒤 클러스터는 전체 노드가 동일 공인 주소를 사용하고 포트로만 구분. 안내 ssh 명령의
 * {@code -p} 누락 시 22 번으로 연결돼 어느 노드에도 미도달.
 */
class VmClusterSshAccessServiceImplTest extends AbstractUnitTest {

    private static final String PEM = "-----BEGIN RSA PRIVATE KEY-----\nFAKE\n-----END RSA PRIVATE KEY-----\n";

    private ProvisioningService provisioningService;
    private VmClusterSshAccessServiceImpl service;

    @BeforeEach
    void setUp() {
        ObjectMapper mapper = new ObjectMapper();
        VmClusterRepository repository = mock(VmClusterRepository.class);
        provisioningService = mock(ProvisioningService.class);
        ClusterSshUserResolver userResolver = mock(ClusterSshUserResolver.class);
        ClusterSshJumpResolver jumpResolver = mock(ClusterSshJumpResolver.class);

        VmClusterEntity cluster = new VmClusterEntity();
        cluster.setClusterName("e2e-proxmox");
        cluster.setStackName("e2e-proxmox-stack");
        when(repository.findFirstByClusterNameOrderByCreatedAtDesc(anyString())).thenReturn(Optional.of(cluster));
        when(userResolver.resolve(any(Map.class))).thenReturn("ubuntu");

        service = new VmClusterSshAccessServiceImpl(
                repository, provisioningService, mock(PulumiProperties.class), mapper, jumpResolver, userResolver);
    }

    private void stackOutputs(List<Map<String, Object>> nodes) {
        when(provisioningService.stackOutputs(anyString(), anyBoolean(), any()))
                .thenReturn(Map.of("sshPrivateKeyPem", PEM, "nodes", nodes));
    }

    @Test
    void natCluster_sharedPublicIp_eachNodeKeepsItsOwnPort() {
        stackOutputs(List.of(
                Map.of("role", "master", "privateIp", "192.168.0.200", "publicIp", "203.0.113.10", "sshPort", 2200),
                Map.of("role", "worker", "privateIp", "192.168.0.201", "publicIp", "203.0.113.10", "sshPort", 2201)));

        SshKeyResult result = service.issueSshKey("e2e-proxmox");

        assertThat(result.nodes()).extracting(SshKeyResult.NodeSshInfo::sshPort).containsExactly(2200, 2201);
        assertThat(result.nodes())
                .extracting(SshKeyResult.NodeSshInfo::sshCommand)
                .containsExactly(
                        "ssh -p 2200 -i e2e-proxmox-ssh.pem ubuntu@203.0.113.10",
                        "ssh -p 2201 -i e2e-proxmox-ssh.pem ubuntu@203.0.113.10");
    }

    @Test
    void standardCluster_port22_omitsPortOption() {
        // 노드별 IP 가 분리된 provider 는 -p 불필요. 붙으면 안내 명령만 길어짐.
        stackOutputs(List.of(Map.of("role", "master", "privateIp", "10.0.0.1", "publicIp", "1.2.3.4", "sshPort", 22)));

        SshKeyResult result = service.issueSshKey("demo");

        assertThat(result.nodes().get(0).sshCommand()).isEqualTo("ssh -i demo-ssh.pem ubuntu@1.2.3.4");
    }

    @Test
    void legacyStack_withoutSshPort_fallsBackTo22() {
        stackOutputs(List.of(Map.of("role", "master", "privateIp", "10.0.0.1", "publicIp", "1.2.3.4")));

        SshKeyResult result = service.issueSshKey("legacy");

        assertThat(result.nodes().get(0).sshPort()).isEqualTo(22);
        assertThat(result.nodes().get(0).sshCommand()).doesNotContain("-p");
    }
}
