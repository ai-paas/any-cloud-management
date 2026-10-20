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
 * 스냅샷은 기본값이 반영된 config 를 참조해야 함.
 *
 * <p>사용자 전송 원본을 참조하면 {@code applyDefaults} 주입 값이 전부 누락. 모니터링은 체크박스
 * 기본값이 활성이라 프론트가 비활성화할 때만 값을 전송하고, 그 결과 활성 상태로 생성한
 * 클러스터의 {@code enableMonitoring} 이 null 로 고정.
 *
 * <p>{@link RequestedAddons} 는 null 을 활성으로 해석하지 않으므로 애드온 미등록. 프로비저닝은
 * 성공하고 클러스터도 READY 로 전이해 모니터링 화면만 비어 있음.
 */
@MockitoSettings(strictness = Strictness.LENIENT)
class DefaultedFlagsReachTheSnapshotTest extends AbstractUnitTest {

    @Mock
    com.aipaas.anycloud.domain.provisioning.convergence.VmClusterComponentRepository componentRepository;

    @Mock
    com.aipaas.anycloud.domain.provisioning.convergence.RequestedAddonInspector addonInspector;

    private final ObjectMapper mapper = new ObjectMapper();

    /** 프론트가 모니터링 활성 상태로 전송하는 경우. 값 자체를 포함하지 않음. */
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
                new VmClusterPayloadServiceImpl(mapper, componentRepository, addonInspector, null);
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
        // 스냅샷이 정확해도 카탈로그 id 불일치 시 미설치. 최종 단계까지 검증.
        assertThat(RequestedAddons.catalogEntries(snapshotOf(requestWithoutMonitoringFlag())))
                .containsKey("kube-prometheus-stack");
    }

    @Test
    void anExplicitOptOutIsRespected() throws Exception {
        // 비활성 요청이 활성으로 처리되면 사용자가 의도하지 않은 자원이 클러스터에 설치.
        ProvisionClusterRequest cluster = requestWithoutMonitoringFlag();
        cluster.getConfig().put("anycloud-k8s:enableMonitoring", "false");

        assertThat(snapshotOf(cluster).getEnableMonitoring()).isFalse();
        assertThat(RequestedAddons.catalogEntries(snapshotOf(cluster))).doesNotContainKey("kube-prometheus-stack");
    }

    @Test
    void flagsTheUserDidSendSurvive() throws Exception {
        // 기본값 참조 과정에서 사용자 전송 값 덮어쓰기 금지.
        assertThat(snapshotOf(requestWithoutMonitoringFlag()).getEnableIngress())
                .isFalse();
    }
}
