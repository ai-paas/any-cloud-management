package com.aipaas.anycloud.domain.provisioning.payload;

import static org.assertj.core.api.Assertions.assertThat;

import com.aipaas.anycloud.domain.credential.ResolvedCspCredential;
import com.aipaas.anycloud.domain.provisioning.api.request.ProvisionClusterRequest;
import com.aipaas.anycloud.domain.provisioning.convergence.RequestedAddons;
import com.aipaas.anycloud.domain.provisioning.model.SupportedProvisioningProvider;
import com.aipaas.anycloud.domain.provisioning.model.VmClusterInternalRequestSnapshot;
import com.aipaas.anycloud.domain.provisioning.payload.internal.VmClusterPayloadServiceImpl;
import com.aipaas.anycloud.domain.provisioning.preflight.validation.ProvisioningConfigRules;
import com.aipaas.anycloud.testsupport.AbstractUnitTest;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.aipaas.cluster.provisioning.api.ProvisioningRequest;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

/**
 * 스냅샷은 기본값이 채워진 config 를 읽어야 한다.
 *
 * <p>사용자가 보낸 원본을 읽으면 {@code applyDefaults} 가 넣은 값이 전부 빠진다. 모니터링은
 * 체크박스가 기본 켜짐이라 프론트가 끌 때만 값을 보내는데, 그러면 켠 채로 만든 클러스터의
 * {@code enableMonitoring} 이 null 로 굳는다.
 *
 * <p>{@link RequestedAddons} 는 null 을 켜짐으로 읽지 않으므로 애드온이 등록되지 않는다.
 * 프로비저닝은 성공하고 클러스터도 READY 가 되어, 모니터링 화면이 빈 것만 남는다.
 */
@MockitoSettings(strictness = Strictness.LENIENT)
class DefaultedFlagsReachTheSnapshotTest extends AbstractUnitTest {

    @Mock
    com.aipaas.anycloud.domain.provisioning.convergence.VmClusterComponentRepository componentRepository;

    @Mock
    com.aipaas.anycloud.domain.provisioning.convergence.RequestedAddonInspector addonInspector;

    private final ObjectMapper mapper = new ObjectMapper();

    /** 프론트가 모니터링을 켠 채로 보낼 때 — 값 자체를 싣지 않는다. */
    private ProvisionClusterRequest requestWithoutMonitoringFlag() {
        ProvisionClusterRequest cluster = new ProvisionClusterRequest();
        cluster.setClusterProvider("GCP");
        cluster.setClusterName("demo");
        cluster.setRegion("asia-northeast3");
        cluster.setConfig(new LinkedHashMap<>(Map.of(
                "anycloud-k8s:masterCount", "1",
                "anycloud-k8s:workerCount", "1",
                "anycloud-k8s:enableIngress", "false")));
        return cluster;
    }

    private VmClusterInternalRequestSnapshot snapshotOf(ProvisionClusterRequest cluster) throws Exception {
        Map<String, String> defaulted = new LinkedHashMap<>(cluster.getConfig());
        ProvisioningConfigRules.applyDefaults(SupportedProvisioningProvider.GCP, defaulted);

        VmClusterPayloadServiceImpl service =
                new VmClusterPayloadServiceImpl(mapper, componentRepository, addonInspector);
        String json = service.serializeRequestSnapshot(
                cluster,
                ProvisioningRequest.builder().provider("GCP").config(defaulted).build(),
                ResolvedCspCredential.builder().environment(Map.of()).build());
        return mapper.readValue(json, VmClusterInternalRequestSnapshot.class);
    }

    @Test
    void monitoringStaysOnWhenTheRequestOmitsTheFlag() throws Exception {
        assertThat(snapshotOf(requestWithoutMonitoringFlag()).getEnableMonitoring())
                .as("기본값이 스냅샷에 닿지 않으면 모니터링이 조용히 꺼진다")
                .isTrue();
    }

    @Test
    void theMonitoringAddonIsEnrolled() throws Exception {
        // 스냅샷이 맞아도 카탈로그 id 가 어긋나면 설치되지 않는다. 끝까지 확인한다.
        assertThat(RequestedAddons.catalogEntries(snapshotOf(requestWithoutMonitoringFlag())))
                .containsKey("kube-prometheus-stack");
    }

    @Test
    void anExplicitOptOutIsRespected() throws Exception {
        // 껐는데 켜지면 사용자가 원치 않은 자원이 클러스터에 깔린다.
        ProvisionClusterRequest cluster = requestWithoutMonitoringFlag();
        cluster.getConfig().put("anycloud-k8s:enableMonitoring", "false");

        assertThat(snapshotOf(cluster).getEnableMonitoring()).isFalse();
        assertThat(RequestedAddons.catalogEntries(snapshotOf(cluster))).doesNotContainKey("kube-prometheus-stack");
    }

    @Test
    void flagsTheUserDidSendSurvive() throws Exception {
        // 기본값을 읽느라 사용자가 보낸 값을 덮으면 안 된다.
        assertThat(snapshotOf(requestWithoutMonitoringFlag()).getEnableIngress())
                .isFalse();
    }
}
