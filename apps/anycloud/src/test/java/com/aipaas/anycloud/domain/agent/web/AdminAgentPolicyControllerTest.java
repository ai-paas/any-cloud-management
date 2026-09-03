package com.aipaas.anycloud.domain.agent.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.aipaas.anycloud.common.error.handler.GlobalExceptionHandler;
import com.aipaas.anycloud.domain.agent.policy.AgentPolicyValidator;
import com.aipaas.anycloud.domain.audit.AuditLogger;
import com.aipaas.anycloud.domain.cluster.ClusterService;
import com.aipaas.anycloud.testsupport.AbstractUnitTest;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.aipaas.cluster.agent.runtime.AgentPolicySnapshot;
import io.aipaas.cluster.agent.runtime.KubeResourceService;
import io.aipaas.cluster.agent.runtime.KubeRoutingException;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * {@link AdminAgentPolicyController} PUT 회귀 — validator 통합 + agent push + audit 흐름 보호.
 */
class AdminAgentPolicyControllerTest extends AbstractUnitTest {

    @Mock
    KubeResourceService kubeResourceService;

    @Mock
    AuditLogger auditLogger;

    @Mock
    ClusterService clusterService;

    @Mock
    com.aipaas.anycloud.domain.agent.policy.AgentPolicyAuditService policyAuditService;

    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        // controller 가 AgentPolicyAuditService 에 위임. fleet audit 단위 테스트는 service 측에서.
        // merge / diff logic 도 service. 실제 구현체 주입 — 행동 보존 검증.
        mvc = MockMvcBuilders.standaloneSetup(new AdminAgentPolicyController(
                        kubeResourceService,
                        new AgentPolicyValidator(), // 실제 validator — 룰 trigger 검증
                        auditLogger,
                        clusterService,
                        policyAuditService,
                        new com.aipaas.anycloud.domain.agent.policy.internal.AgentPolicyMergeServiceImpl(),
                        new com.aipaas.anycloud.domain.agent.policy.AgentPolicyDiffCalculator()))
                .setControllerAdvice(new GlobalExceptionHandler(new ObjectMapper()))
                .build();
    }

    /**
     * ww 의 diff 계산을 위해 applyChange 가 이전 snapshot 을 fetch — mock 이 빈 snapshot 반환.
     * 다른 의도가 없으면 모든 list 가 빈 상태로 시작. force/422 케이스도 동일하게 사용.
     */
    private void stubEmptyBeforeSnapshot() {
        when(kubeResourceService.getAgentConfig(anyString()))
                .thenReturn(new AgentPolicySnapshot(
                        List.of(), false, List.of(), List.of(), List.of(), false, null, null, null));
    }

    @Test
    void put_success_appliesAndAudits() throws Exception {
        stubEmptyBeforeSnapshot();
        // secrets 가 deny 에 들어가 있어 HIGH severity 없음 — 정상 적용 예상
        when(kubeResourceService.applyAgentConfig(
                        eq("c-1"), anyString(), anyString(), anyString(), anyString(), anyString()))
                .thenReturn("99999");

        String body =
                """
				{
				  "allowedNamespaces": ["monitoring", "app"],
				  "allowedCommands": ["LIST_PODS", "LIST_RESOURCES", "GET_LOG"],
				  "allowedCharts": [],
				  "allowedExecNamespaces": [],
				  "resourcePolicy": {
				    "mode": "allow_all_discovered",
				    "deny": [{ "kind": "secrets" }, { "kind": "roles" }, { "kind": "rolebindings" },
				             { "kind": "clusterroles" }, { "kind": "clusterrolebindings" },
				             { "kind": "validatingwebhookconfigurations" },
				             { "kind": "mutatingwebhookconfigurations" },
				             { "kind": "configmaps", "namespace": "kube-system" }],
				    "allow": []
				  },
				  "force": false
				}
				""";

        mvc.perform(put("/v1/admin/clusters/c-1/agent-policy")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.clusterName").value("c-1"))
                .andExpect(jsonPath("$.data.appliedResourceVersion").value("99999"));

        verify(kubeResourceService, times(1))
                .applyAgentConfig(eq("c-1"), anyString(), anyString(), anyString(), anyString(), anyString());
        verify(auditLogger, times(1)).record(any());
    }

    @Test
    void put_highSeverityWarning_rejectsWith422_unlessForce() throws Exception {
        stubEmptyBeforeSnapshot();
        // allow_all_discovered + secrets 누락 → MISSING_SECRETS_DENY (HIGH)
        String body =
                """
				{
				  "allowedNamespaces": ["*"],
				  "allowedCommands": ["LIST_PODS"],
				  "allowedCharts": [],
				  "resourcePolicy": {
				    "mode": "allow_all_discovered",
				    "deny": [],
				    "allow": []
				  },
				  "force": false
				}
				""";

        mvc.perform(put("/v1/admin/clusters/c-1/agent-policy")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.data.code").value("POLICY_VALIDATION_FAILED"))
                .andExpect(jsonPath("$.data.highestSeverity").value("HIGH"));

        verify(kubeResourceService, never())
                .applyAgentConfig(anyString(), anyString(), anyString(), anyString(), anyString(), anyString());
        verify(auditLogger, never()).record(any());
    }

    @Test
    void put_force_overridesHighSeverity() throws Exception {
        stubEmptyBeforeSnapshot();
        when(kubeResourceService.applyAgentConfig(
                        eq("c-1"), anyString(), anyString(), anyString(), anyString(), anyString()))
                .thenReturn("100");

        // force=true 면 HIGH severity 무시
        String body =
                """
				{
				  "allowedNamespaces": ["*"],
				  "allowedCommands": ["LIST_PODS"],
				  "allowedCharts": [],
				  "resourcePolicy": {
				    "mode": "allow_all_discovered",
				    "deny": [],
				    "allow": []
				  },
				  "force": true
				}
				""";

        mvc.perform(put("/v1/admin/clusters/c-1/agent-policy")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.appliedResourceVersion").value("100"));

        verify(kubeResourceService)
                .applyAgentConfig(eq("c-1"), anyString(), anyString(), anyString(), anyString(), anyString());
        verify(auditLogger).record(any());
    }

    @Test
    void put_agentUnavailable_returns503() throws Exception {
        stubEmptyBeforeSnapshot();
        when(kubeResourceService.applyAgentConfig(
                        anyString(), anyString(), anyString(), anyString(), anyString(), anyString()))
                .thenThrow(new KubeRoutingException("No active agent session"));

        // 정책 자체는 통과해야 503 이 검증됨 — secrets 가 deny 에 들어가도록
        String body =
                """
				{
				  "allowedNamespaces": ["monitoring"],
				  "allowedCommands": ["LIST_PODS"],
				  "allowedCharts": [],
				  "resourcePolicy": {
				    "mode": "allow_all_discovered",
				    "deny": [{ "kind": "secrets" }, { "kind": "roles" }, { "kind": "rolebindings" },
				             { "kind": "clusterroles" }, { "kind": "clusterrolebindings" },
				             { "kind": "validatingwebhookconfigurations" },
				             { "kind": "mutatingwebhookconfigurations" },
				             { "kind": "configmaps", "namespace": "kube-system" }],
				    "allow": []
				  },
				  "force": false
				}
				""";

        mvc.perform(put("/v1/admin/clusters/c-1/agent-policy")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.data.code").value("AGENT_UNAVAILABLE"));

        verify(auditLogger, never()).record(any());
    }

    @Test
    void put_nullResourcePolicy_acceptedAsLegacyBehavior() throws Exception {
        stubEmptyBeforeSnapshot();
        // resource_policy null → NO_RESOURCE_POLICY INFO warning. HIGH 아님 → 정상 적용.
        when(kubeResourceService.applyAgentConfig(
                        eq("c-1"), anyString(), anyString(), anyString(), anyString(), eq("")))
                .thenReturn("50");

        String body =
                """
				{
				  "allowedNamespaces": ["monitoring"],
				  "allowedCommands": ["LIST_PODS"],
				  "allowedCharts": [],
				  "resourcePolicy": null,
				  "force": false
				}
				""";

        mvc.perform(put("/v1/admin/clusters/c-1/agent-policy")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.appliedResourceVersion").value("50"));
    }

    // =================== GGG — CCC merge-patch (RFC 7396) tests ===================

    private static final MediaType MERGE_PATCH_JSON = MediaType.valueOf("application/merge-patch+json");

    @Test
    void mergePatch_explicitNullResourcePolicy_clearsField() throws Exception {
        // 현재 snapshot 에 resource_policy 가 있음
        AgentPolicySnapshot current = new AgentPolicySnapshot(
                List.of("monitoring"),
                false,
                List.of("LIST_PODS"),
                List.of("prom/kube-prom:1.0.0-2.0.0"),
                List.of(),
                false,
                new AgentPolicySnapshot.ResourcePolicy(
                        "strict", List.of(new AgentPolicySnapshot.ResourceRule("pods", null)), List.of()),
                null,
                null);
        when(kubeResourceService.getAgentConfig("c-1")).thenReturn(current);
        when(kubeResourceService.applyAgentConfig(
                        eq("c-1"),
                        anyString(),
                        anyString(),
                        anyString(),
                        anyString(),
                        eq(""))) // resource_policy YAML 이 빈 string
                .thenReturn("90");

        // RFC 7396 — resourcePolicy: null 명시 → 비우기
        String body = """
				{ "resourcePolicy": null }
				""";

        mvc.perform(patch("/v1/admin/clusters/c-1/agent-policy")
                        .contentType(MERGE_PATCH_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.method").value("MERGE_PATCH"))
                .andExpect(jsonPath("$.data.appliedResourceVersion").value("90"))
                .andExpect(jsonPath("$.data.diff.resourcePolicyChanged").value(true));
    }

    @Test
    void mergePatch_omittedField_keepsCurrentValue() throws Exception {
        // 현재 snapshot 에 모든 list 가 채워져 있음
        AgentPolicySnapshot current = new AgentPolicySnapshot(
                List.of("monitoring", "app"),
                false,
                List.of("LIST_PODS", "GET_LOG"),
                List.of("prom/kube-prom:1.0.0-2.0.0"),
                List.of(),
                false,
                null,
                null,
                null);
        when(kubeResourceService.getAgentConfig("c-1")).thenReturn(current);
        when(kubeResourceService.applyAgentConfig(
                        eq("c-1"), anyString(), anyString(), anyString(), anyString(), anyString()))
                .thenReturn("91");

        // allowedCharts 만 변경 — 다른 field 는 omit → 현재 값 유지
        String body = """
				{ "allowedCharts": ["new-repo/new-chart:1.0.0-2.0.0"] }
				""";

        mvc.perform(patch("/v1/admin/clusters/c-1/agent-policy")
                        .contentType(MERGE_PATCH_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.diff.allowedCharts.added[0]").value("new-repo/new-chart:1.0.0-2.0.0"))
                .andExpect(jsonPath("$.data.diff.allowedCharts.removed[0]").value("prom/kube-prom:1.0.0-2.0.0"));
    }

    @Test
    void mergePatch_explicitNullList_clearsToEmpty() throws Exception {
        AgentPolicySnapshot current = new AgentPolicySnapshot(
                List.of("monitoring", "app"),
                false,
                List.of("LIST_PODS"),
                List.of("prom/kube-prom:1.0.0-2.0.0"),
                List.of(),
                false,
                null,
                null,
                null);
        when(kubeResourceService.getAgentConfig("c-1")).thenReturn(current);
        when(kubeResourceService.applyAgentConfig(
                        eq("c-1"), anyString(), anyString(), anyString(), anyString(), anyString()))
                .thenReturn("92");

        // RFC 7396 — allowedExecNamespaces: null → 빈 list 로 clear
        String body = """
				{ "allowedExecNamespaces": null }
				""";

        mvc.perform(patch("/v1/admin/clusters/c-1/agent-policy")
                        .contentType(MERGE_PATCH_JSON)
                        .content(body))
                .andExpect(status().isOk());
        // 다른 list 는 현재 값 유지됐는지 verify (diff 가 비어있어야 함)
    }

    @Test
    void mergePatch_invalidBody_returns400() throws Exception {
        mvc.perform(patch("/v1/admin/clusters/c-1/agent-policy")
                        .contentType(MERGE_PATCH_JSON)
                        .content("\"not-an-object\""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.data.code").value("INVALID_INPUT_VALUE"));
    }

    @Test
    void legacyPatch_explicitNullResourcePolicy_keepsCurrent() throws Exception {
        // 같은 body 의 application/json 은 legacy 동작 — null = keep
        // current snapshot 이 HIGH warning 유발 안 하도록 — secrets / RBAC / webhook 모두 deny
        AgentPolicySnapshot.ResourcePolicy currentPolicy = new AgentPolicySnapshot.ResourcePolicy(
                "allow_all_discovered",
                List.of(
                        new AgentPolicySnapshot.ResourceRule("secrets", null),
                        new AgentPolicySnapshot.ResourceRule("roles", null),
                        new AgentPolicySnapshot.ResourceRule("rolebindings", null),
                        new AgentPolicySnapshot.ResourceRule("clusterroles", null),
                        new AgentPolicySnapshot.ResourceRule("clusterrolebindings", null),
                        new AgentPolicySnapshot.ResourceRule("validatingwebhookconfigurations", null),
                        new AgentPolicySnapshot.ResourceRule("mutatingwebhookconfigurations", null),
                        new AgentPolicySnapshot.ResourceRule("configmaps", "kube-system")),
                List.of());
        AgentPolicySnapshot current = new AgentPolicySnapshot(
                List.of("monitoring"),
                false,
                List.of("LIST_PODS"),
                List.of("prom/kube-prom:1.0.0-2.0.0"),
                List.of(),
                false,
                currentPolicy,
                null,
                null);
        when(kubeResourceService.getAgentConfig("c-1")).thenReturn(current);
        // resource_policy YAML 이 "allow_all_discovered" 로 시작 — 현재 값이 유지됐다는 신호
        when(kubeResourceService.applyAgentConfig(
                        eq("c-1"),
                        anyString(),
                        anyString(),
                        anyString(),
                        anyString(),
                        org.mockito.ArgumentMatchers.argThat(
                                yaml -> yaml != null && yaml.contains("allow_all_discovered"))))
                .thenReturn("93");

        String body = """
				{ "resourcePolicy": null }
				""";

        mvc.perform(patch("/v1/admin/clusters/c-1/agent-policy")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.method").value("PATCH"));
    }

    // =================== fleet audit (smoke test, real logic 은 service test) ===================
    //
    // 상세 fleet audit logic 회귀는 AgentPolicyAuditServiceImplTest 가 보호.
    // 본 test 는 controller→service 위임만 검증.

    @Test
    void fleetAudit_delegatesToService() throws Exception {
        java.util.LinkedHashMap<String, Object> stub = new java.util.LinkedHashMap<>();
        stub.put("totalClusters", 3);
        stub.put("scannedClusters", 2);
        stub.put("unreachableClusters", 1);
        stub.put("bySeverity", java.util.Map.of("HIGH", 1));
        stub.put("durationMs", 42L);
        stub.put("clusters", java.util.List.of());
        when(policyAuditService.runFleetAudit()).thenReturn(stub);

        mvc.perform(get("/v1/admin/agent/policy/audit"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalClusters").value(3))
                .andExpect(jsonPath("$.data.unreachableClusters").value(1));
    }
}
