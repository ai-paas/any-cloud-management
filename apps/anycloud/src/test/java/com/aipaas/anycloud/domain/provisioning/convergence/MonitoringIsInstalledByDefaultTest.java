package com.aipaas.anycloud.domain.provisioning.convergence;

import static org.assertj.core.api.Assertions.assertThat;

import com.aipaas.anycloud.domain.addon.model.AddonType;
import com.aipaas.anycloud.domain.provisioning.model.SupportedProvisioningProvider;
import com.aipaas.anycloud.domain.provisioning.model.VmClusterInternalRequestSnapshot;
import com.aipaas.anycloud.domain.provisioning.preflight.validation.ProvisioningConfigRules;
import com.aipaas.anycloud.testsupport.AbstractUnitTest;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * 프로비저닝 완료 직후 모니터링 조회.
 *
 * <p>클러스터 생성 후 모니터링 화면 진입 시 Prometheus 부재로 빈 그래프만 표시. 지표 수집을
 * 기본 활성으로 두고 불필요한 경우 비활성화하는 방식 적용.
 */
class MonitoringIsInstalledByDefaultTest extends AbstractUnitTest {

    private static final String KEY = "anycloud-k8s:enableMonitoring";

    private Map<String, String> defaulted(Map<String, String> given) {
        Map<String, String> config = new HashMap<>(given);
        ProvisioningConfigRules.applyDefaults(SupportedProvisioningProvider.OPENSTACK, config);
        return config;
    }

    @Test
    void aNewRequestGetsMonitoringWithoutAskingForIt() {
        assertThat(defaulted(Map.of())).containsEntry(KEY, "true");
    }

    @Test
    void anExplicitOptOutIsRespected() {
        // 지표 수집이 자원을 소모하는 소규모 클러스터 존재. 기본값이 사용자 선택을 덮으면 안 됨.
        assertThat(defaulted(Map.of(KEY, "false"))).containsEntry(KEY, "false");
    }

    @Test
    void theFlagIsCheckedForTyposLikeTheOthers() {
        // "True", "1", "yes" 는 Boolean.parseBoolean 이 조용히 false 로 읽는다. 끈 줄 모르고 켠
        // 줄 아는 상태가 가장 나쁘다.
        Map<String, String> config = defaulted(Map.of(KEY, "yes"));

        assertThat(config).containsEntry(KEY, "yes");
        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                        ProvisioningConfigRules.validateRequiredConfig(SupportedProvisioningProvider.OPENSTACK, config))
                .hasMessageContaining(KEY);
    }

    @Test
    void theRequestEnrollsTheMonitoringStack() {
        VmClusterInternalRequestSnapshot spec = VmClusterInternalRequestSnapshot.builder()
                .enableMonitoring(true)
                .build();

        assertThat(RequestedAddons.catalogEntries(spec)).containsEntry("kube-prometheus-stack", AddonType.MONITORING);
    }

    @Test
    void anOlderRequestWithoutTheFlagIsLeftAlone() {
        // 이미 돌고 있는 클러스터의 스냅샷에는 이 값이 없다. null 을 켜짐으로 읽으면 멀쩡한
        // 클러스터가 애드온 누락으로 DEGRADED 전이.
        VmClusterInternalRequestSnapshot spec =
                VmClusterInternalRequestSnapshot.builder().build();

        assertThat(RequestedAddons.catalogEntries(spec)).doesNotContainKey("kube-prometheus-stack");
    }

    @Test
    void optingOutKeepsTheStackAway() {
        VmClusterInternalRequestSnapshot spec = VmClusterInternalRequestSnapshot.builder()
                .enableMonitoring(false)
                .build();

        assertThat(RequestedAddons.catalogEntries(spec)).doesNotContainKey("kube-prometheus-stack");
    }

    @Test
    void theCheckboxReachesTheConfigKey() {
        // 화면이 끄기를 보내도 flatten 이 빠뜨리면 preflight 기본값이 다시 켠다.
        var spec = new com.aipaas.anycloud.domain.provisioning.api.request.ClusterSpecRequest(
                null, null, null, null, null, null, null, null, null, null, null, false, null);

        var request = new com.aipaas.anycloud.domain.provisioning.api.request.VmCreateRequest();
        request.setSpec(spec);
        Map<String, String> flat =
                com.aipaas.anycloud.domain.provisioning.support.ProvisioningConfigFlattener.flatten(request);

        assertThat(flat).containsEntry(KEY, "false");
    }
}
