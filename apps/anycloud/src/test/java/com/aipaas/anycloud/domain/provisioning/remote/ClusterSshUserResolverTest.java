package com.aipaas.anycloud.domain.provisioning.remote;

import static org.assertj.core.api.Assertions.assertThat;

import com.aipaas.anycloud.domain.provisioning.VmClusterEntity;
import com.aipaas.anycloud.domain.provisioning.properties.PulumiProperties;
import com.aipaas.anycloud.domain.provisioning.remote.internal.ClusterSshUserResolver;
import com.aipaas.anycloud.testsupport.AbstractUnitTest;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * 접속 사용자는 그 클러스터를 만든 스택이 정한다.
 *
 * <p>전역 기본값으로 붙으면 Alibaba 만 publickey 거절로 막힌다 — 그 이미지에는 ubuntu 계정이
 * 없고 키가 root 에 들어간다. 화면에는 노드가 다 올라온 것으로 보여 원인이 드러나지 않는다.
 */
class ClusterSshUserResolverTest extends AbstractUnitTest {

    private final ClusterSshUserResolver resolver = new ClusterSshUserResolver(new ObjectMapper(), properties());

    private PulumiProperties properties() {
        PulumiProperties properties = new PulumiProperties();
        properties.setSshUser("ubuntu");
        return properties;
    }

    private VmClusterEntity cluster(String rawOutputs) {
        VmClusterEntity cluster = new VmClusterEntity();
        cluster.setClusterName("demo");
        cluster.setRawOutputs(rawOutputs);
        return cluster;
    }

    @Test
    void theStackOutputWins() {
        assertThat(resolver.resolve(cluster("{\"sshUser\":\"root\"}"))).isEqualTo("root");
        assertThat(resolver.resolve(Map.of("sshUser", "root"))).isEqualTo("root");
    }

    @Test
    void clustersCreatedBeforeTheOutputExistedStillResolve() {
        // sshUser 를 내보내기 전에 만든 스택이 남아 있다. 여기서 막으면 그 클러스터가 접속 불가가 된다.
        assertThat(resolver.resolve(cluster("{\"provider\":\"aws\"}"))).isEqualTo("ubuntu");
        assertThat(resolver.resolve(cluster(null))).isEqualTo("ubuntu");
        assertThat(resolver.resolve((VmClusterEntity) null)).isEqualTo("ubuntu");
        assertThat(resolver.resolve(Map.of())).isEqualTo("ubuntu");
    }

    @Test
    void brokenOutputsDoNotBreakAccess() {
        assertThat(resolver.resolve(cluster("not json"))).isEqualTo("ubuntu");
        assertThat(resolver.resolve(cluster("{\"sshUser\":\"  \"}"))).isEqualTo("ubuntu");
    }
}
