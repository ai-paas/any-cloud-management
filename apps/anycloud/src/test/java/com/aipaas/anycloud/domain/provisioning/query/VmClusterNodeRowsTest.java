package com.aipaas.anycloud.domain.provisioning.query;

import static org.assertj.core.api.Assertions.assertThat;

import com.aipaas.anycloud.domain.provisioning.VmClusterEntity;
import com.aipaas.anycloud.domain.provisioning.model.VmClusterStatus;
import com.aipaas.anycloud.testsupport.AbstractUnitTest;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * 노드 전용 테이블 부재. Pulumi outputs 의 nodes 배열이 유일한 소스.
 *
 * <p>해당 배열에 이름과 상태가 없어 목록 표시에는 이름 생성과 클러스터 상태 상속이 필요.
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
        // YAML 프로그램은 배열 직접 출력이 불가해 문자열로 래핑. 소비자는 양쪽 모두 수용 필요.
        String escaped = NODES.replace("\\", "\\\\").replace("\"", "\\\"");
        List<VmClusterNodeRows.Row> rows = VmClusterNodeRows.of(MAPPER, cluster("{\"nodes\":\"" + escaped + "\"}"));

        assertThat(rows).hasSize(3);
    }

    @Test
    void aClusterWithoutNodesStillGetsOneRow() {
        /*
         * 빈 목록 반환 시 생성 중이거나 실패한 클러스터가 목록에서 누락. 화면이 보완하면
         * 자르는 지점과 세는 지점이 분리돼 다음 페이지가 빈 값.
         */
        for (String outputs : new String[] {null, "", "{}"}) {
            assertThat(VmClusterNodeRows.of(MAPPER, cluster(outputs)))
                    .singleElement()
                    .satisfies(row -> {
                        assertThat(row.pending()).isTrue();
                        assertThat(row.clusterName()).isEqualTo("demo");
                        assertThat(row.role()).isNull();
                    });
        }
    }

    @Test
    void survivesGarbageInsteadOfHidingTheWholeList() {
        // 클러스터 하나의 outputs 손상으로 다른 클러스터 노드까지 누락되면 안 됨.
        assertThat(VmClusterNodeRows.of(MAPPER, cluster("not json")))
                .singleElement()
                .satisfies(row -> assertThat(row.pending()).isTrue());
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
        // 프로비저닝 전에는 노드 부재. null 반환 시 화면의 매 호출 방어 코드 필요.
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
