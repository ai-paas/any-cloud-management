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
 * 접속 사용자는 해당 클러스터를 생성한 스택이 결정.
 *
 * <p>전역 기본값으로 접속하면 Alibaba 만 publickey 거절. 해당 이미지에는 ubuntu 계정이 없고
 * 키가 root 에 등록. 화면에는 노드가 정상 기동한 것으로 표시돼 원인 파악 지연.
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
        // sshUser 출력 도입 이전 생성 스택 잔존. 이 지점에서 차단하면 해당 클러스터 접속 불가.
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
