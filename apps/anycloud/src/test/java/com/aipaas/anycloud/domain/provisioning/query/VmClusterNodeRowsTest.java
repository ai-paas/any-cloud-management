package com.aipaas.anycloud.domain.provisioning.query;

import static org.assertj.core.api.Assertions.assertThat;

import com.aipaas.anycloud.domain.provisioning.VmClusterEntity;
import com.aipaas.anycloud.domain.provisioning.model.VmClusterStatus;
import com.aipaas.anycloud.testsupport.AbstractUnitTest;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * 노드는 자기 테이블이 없다. Pulumi outputs 의 nodes 배열이 전부다.
 *
 * <p>그 배열에는 이름도 상태도 없어서, 목록에 세우려면 이름을 만들고 상태는 클러스터에서 물려받아야 한다.
 */
class VmClusterNodeRowsTest extends AbstractUnitTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final String NODES = "[{\"role\":\"master\",\"instanceId\":\"i-1\",\"privateIp\":\"10.0.0.1\","
            + "\"publicIp\":\"1.2.3.4\",\"publicDns\":\"1.2.3.4\"},"
            + "{\"role\":\"worker\",\"instanceId\":\"i-2\",\"privateIp\":\"10.0.0.2\","
            + "\"publicIp\":\"1.2.3.5\",\"publicDns\":\"1.2.3.5\"},"
            + "{\"role\":\"worker\",\"instanceId\":\"i-3\",\"privateIp\":\"10.0.0.3\","
            + "\"publicIp\":\"1.2.3.6\",\"publicDns\":\"1.2.3.6\"}]";

    private VmClusterEntity cluster(String rawOutputs) {
        VmClusterEntity e = new VmClusterEntity();
        e.setClusterName("demo");
        e.setClusterProvider("OCI");
        e.setRegion("ap-tokyo-1");
        e.setEnvironment("dev");
        e.setProvisioningStatus(VmClusterStatus.READY);
        e.setRawOutputs(rawOutputs);
        return e;
    }

    @Test
    void namesEachNodeByRoleAndOrder() {
        List<VmClusterNodeRows.Row> rows = VmClusterNodeRows.of(MAPPER, cluster("{\"nodes\":" + NODES + "}"));

        assertThat(rows)
                .extracting(VmClusterNodeRows.Row::nodeName)
                .containsExactly("demo-master-0", "demo-worker-0", "demo-worker-1");
    }

    @Test
    void carriesInfraIdentityFromOutputs() {
        VmClusterNodeRows.Row master = VmClusterNodeRows.of(MAPPER, cluster("{\"nodes\":" + NODES + "}"))
                .get(0);

        assertThat(master.role()).isEqualTo("master");
        assertThat(master.instanceId()).isEqualTo("i-1");
        assertThat(master.privateIp()).isEqualTo("10.0.0.1");
        assertThat(master.publicIp()).isEqualTo("1.2.3.4");
    }

    @Test
    void inheritsClusterContextSoTheListCanFilter() {
        VmClusterNodeRows.Row row = VmClusterNodeRows.of(MAPPER, cluster("{\"nodes\":" + NODES + "}"))
                .get(0);

        assertThat(row.clusterName()).isEqualTo("demo");
        assertThat(row.clusterProvider()).isEqualTo("OCI");
        assertThat(row.region()).isEqualTo("ap-tokyo-1");
        assertThat(row.environment()).isEqualTo("dev");
        assertThat(row.infraStatus()).isEqualTo("READY");
    }

    @Test
    void readsNodesEvenWhenPulumiSendsThemAsAJsonString() {
        // YAML 프로그램은 배열을 그대로 못 내보내 문자열로 싼다. 소비자가 양쪽을 모두 받아야 한다.
        String escaped = NODES.replace("\\", "\\\\").replace("\"", "\\\"");
        List<VmClusterNodeRows.Row> rows = VmClusterNodeRows.of(MAPPER, cluster("{\"nodes\":\"" + escaped + "\"}"));

        assertThat(rows).hasSize(3);
    }

    @Test
    void yieldsNothingBeforeProvisioningProducesOutputs() {
        assertThat(VmClusterNodeRows.of(MAPPER, cluster(null))).isEmpty();
        assertThat(VmClusterNodeRows.of(MAPPER, cluster(""))).isEmpty();
        assertThat(VmClusterNodeRows.of(MAPPER, cluster("{}"))).isEmpty();
    }

    @Test
    void survivesGarbageInsteadOfHidingTheWholeList() {
        // 한 클러스터의 outputs 가 깨졌다고 다른 클러스터의 노드까지 사라지면 안 된다.
        assertThat(VmClusterNodeRows.of(MAPPER, cluster("not json"))).isEmpty();
    }

    @Test
    void summarizesCountsAndTheMasterAddress() {
        // 클러스터 목록은 worker 행을 보여주지 않는다. master 주소와 대수만 필요하다.
        var summary = VmClusterNodeRows.summarize(VmClusterNodeRows.of(MAPPER, cluster("{\"nodes\":" + NODES + "}")));

        assertThat(summary.masterCount()).isEqualTo(1);
        assertThat(summary.workerCount()).isEqualTo(2);
        assertThat(summary.masterPrivateIp()).isEqualTo("10.0.0.1");
        assertThat(summary.masterPublicIp()).isEqualTo("1.2.3.4");
    }

    @Test
    void summaryOfNothingIsZeroNotNull() {
        // 프로비저닝 전에는 노드가 없다. null 을 돌려주면 화면이 매번 방어해야 한다.
        var summary = VmClusterNodeRows.summarize(List.of());

        assertThat(summary.masterCount()).isZero();
        assertThat(summary.workerCount()).isZero();
        assertThat(summary.masterPrivateIp()).isNull();
    }

    @Test
    void countsEveryMasterInAnHaControlPlane() {
        String ha = "[{\"role\":\"master\",\"privateIp\":\"10.0.0.1\"},"
                + "{\"role\":\"master\",\"privateIp\":\"10.0.0.2\"},"
                + "{\"role\":\"worker\",\"privateIp\":\"10.0.0.3\"}]";

        var summary = VmClusterNodeRows.summarize(VmClusterNodeRows.of(MAPPER, cluster("{\"nodes\":" + ha + "}")));

        assertThat(summary.masterCount()).isEqualTo(2);
        assertThat(summary.masterPrivateIp()).isEqualTo("10.0.0.1");
    }
}
