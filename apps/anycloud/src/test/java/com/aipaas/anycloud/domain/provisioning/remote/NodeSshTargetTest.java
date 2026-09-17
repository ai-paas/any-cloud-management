package com.aipaas.anycloud.domain.provisioning.remote;

import static org.assertj.core.api.Assertions.assertThat;

import com.aipaas.anycloud.domain.provisioning.remote.ws.NodeSshTarget;
import com.aipaas.anycloud.testsupport.AbstractUnitTest;
import java.net.URI;
import org.junit.jupiter.api.Test;

/** 잘못된 경로로 세션을 여는 것보다 끊는 쪽이 낫다. */
class NodeSshTargetTest extends AbstractUnitTest {

    @Test
    void readsClusterAndHost() {
        NodeSshTarget t = NodeSshTarget.from(URI.create("ws://h/v1/vms/demo/nodes/10.0.0.1/ssh"));

        assertThat(t).isNotNull();
        assertThat(t.vmName()).isEqualTo("demo");
        assertThat(t.host()).isEqualTo("10.0.0.1");
    }

    @Test
    void ignoresQueryString() {
        NodeSshTarget t = NodeSshTarget.from(URI.create("ws://h/v1/vms/demo/nodes/10.0.0.1/ssh?cols=80"));

        assertThat(t).isNotNull();
        assertThat(t.host()).isEqualTo("10.0.0.1");
    }

    @Test
    void handlesTrailingSlash() {
        assertThat(NodeSshTarget.from(URI.create("ws://h/v1/vms/demo/nodes/10.0.0.1/ssh/")))
                .isNotNull();
    }

    @Test
    void returnsNullForOtherPaths() {
        assertThat(NodeSshTarget.from(URI.create("ws://h/v1/vms/demo/nodes"))).isNull();
        assertThat(NodeSshTarget.from(URI.create("ws://h/v1/clusters/demo/pods/ns/p/exec")))
                .isNull();
        assertThat(NodeSshTarget.from(null)).isNull();
    }

    @Test
    void decodesEncodedSegments() {
        NodeSshTarget t = NodeSshTarget.from(URI.create("ws://h/v1/vms/my%2Dcluster/nodes/10.0.0.1/ssh"));

        assertThat(t.vmName()).isEqualTo("my-cluster");
    }
}
