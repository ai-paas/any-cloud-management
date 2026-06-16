package com.aipaas.anycloud.domain.cluster.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.aipaas.anycloud.domain.cluster.NodeDebugPodService;
import com.aipaas.anycloud.domain.cluster.NodeDebugPodService.DebugPodResult;
import com.aipaas.anycloud.domain.cluster.NodeDebugPodService.NodeDebugPodException;
import com.aipaas.anycloud.domain.cluster.kubeconfig.KubeconfigExportService;
import com.aipaas.anycloud.domain.cluster.kubeconfig.KubeconfigExportService.IssuedKubeconfig;
import com.aipaas.anycloud.domain.cluster.kubeconfig.KubeconfigExportService.KubeconfigExportException;
import com.aipaas.anycloud.domain.cluster.kubeconfig.KubeconfigIdentityResolver;
import com.aipaas.anycloud.domain.cluster.kubeconfig.KubeconfigIdentityResolver.ResolvedIdentity;
import com.aipaas.anycloud.testsupport.AbstractUnitTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class ClusterAccessControllerTest extends AbstractUnitTest {

    @Mock
    KubeconfigExportService kubeconfigExportService;

    @Mock
    NodeDebugPodService nodeDebugPodService;

    @Mock
    com.aipaas.anycloud.domain.provisioning.remote.VmClusterSshAccessService vmClusterSshAccessService;

    @Mock
    KubeconfigIdentityResolver kubeconfigIdentityResolver;

    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.standaloneSetup(new ClusterAccessController(
                        kubeconfigExportService,
                        nodeDebugPodService,
                        vmClusterSshAccessService,
                        kubeconfigIdentityResolver))
                .build();
    }

    // ===== kubeconfig (GET 다운로드 — III-61 단일 엔드포인트, identity 는 resolver) =====

    @Test
    void downloadKubeconfig_resolvesIdentityAndReturnsAttachmentYaml() throws Exception {
        when(kubeconfigIdentityResolver.resolve(eq("c1"), any(), any()))
                .thenReturn(new ResolvedIdentity("aipaas-system", "aipaas-admin"));
        when(kubeconfigExportService.issue(eq("c1"), any()))
                .thenReturn(new IssuedKubeconfig(
                        "c1",
                        "aipaas-system",
                        "aipaas-admin",
                        "2026-12-31T23:59:59Z",
                        "apiVersion: v1\nkind: Config\n"));

        mvc.perform(get("/v1/clusters/c1/kubeconfig"))
                .andExpect(status().isOk())
                .andExpect(content().contentType("application/yaml"))
                .andExpect(header().string("Content-Disposition", org.hamcrest.Matchers.containsString("attachment")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("apiVersion: v1")));
    }

    @Test
    void downloadKubeconfig_serviceAccountRequired_returns400() throws Exception {
        // registered cluster + SA 미지정 → resolver 가 SERVICE_ACCOUNT_REQUIRED throw → 400.
        when(kubeconfigIdentityResolver.resolve(eq("c1"), any(), any()))
                .thenThrow(new KubeconfigExportException("SERVICE_ACCOUNT_REQUIRED", "sa required"));

        mvc.perform(get("/v1/clusters/c1/kubeconfig"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.metadata.sourceCode").value("SERVICE_ACCOUNT_REQUIRED"));
    }

    @Test
    void downloadKubeconfig_serviceAccountNotFound_returns404() throws Exception {
        when(kubeconfigIdentityResolver.resolve(eq("c1"), any(), any()))
                .thenReturn(new ResolvedIdentity("default", "ghost"));
        when(kubeconfigExportService.issue(eq("c1"), any()))
                .thenThrow(new KubeconfigExportException("SERVICE_ACCOUNT_NOT_FOUND", "sa missing"));

        mvc.perform(get("/v1/clusters/c1/kubeconfig").param("serviceAccount", "ghost"))
                .andExpect(status().isNotFound());
    }

    @Test
    void downloadKubeconfig_noActiveAgent_returns503() throws Exception {
        when(kubeconfigIdentityResolver.resolve(eq("c1"), any(), any()))
                .thenReturn(new ResolvedIdentity("default", "x"));
        when(kubeconfigExportService.issue(eq("c1"), any()))
                .thenThrow(new KubeconfigExportException("NO_ACTIVE_AGENT", "no stream"));

        mvc.perform(get("/v1/clusters/c1/kubeconfig").param("serviceAccount", "x"))
                .andExpect(status().isServiceUnavailable());
    }

    // ===== node debug pod =====

    @Test
    void createDebugPod_happyPath_returns201() throws Exception {
        when(nodeDebugPodService.create(eq("c1"), any()))
                .thenReturn(new DebugPodResult(
                        "c1", "node-1", "kube-system", "aipaas-node-debug-1", "2026-12-31T23:59:59Z"));

        mvc.perform(post("/v1/clusters/c1/nodes/node-1/debug-pod"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.podName").value("aipaas-node-debug-1"))
                .andExpect(jsonPath("$.data.nodeName").value("node-1"));
    }

    @Test
    void createDebugPod_namespaceNotAllowed_returns403() throws Exception {
        when(nodeDebugPodService.create(eq("c1"), any()))
                .thenThrow(new NodeDebugPodException("NAMESPACE_NOT_ALLOWED", "denied"));

        mvc.perform(post("/v1/clusters/c1/nodes/node-1/debug-pod")).andExpect(status().isForbidden());
    }

    @Test
    void createDebugPod_timeout_returns503WithErrorResponse() throws Exception {
        //  에러는 공통 ErrorResponse — TIMEOUT 은 CLUSTER_CONNECTION_FAILED(503) 매핑,
        // 원래 module 코드는 metadata.sourceCode 로 보존.
        when(nodeDebugPodService.create(eq("c1"), any())).thenThrow(new NodeDebugPodException("TIMEOUT", "timeout"));

        mvc.perform(post("/v1/clusters/c1/nodes/node-1/debug-pod"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("CLUSTER_CONNECTION_FAILED"))
                .andExpect(jsonPath("$.status").value(503))
                .andExpect(jsonPath("$.metadata.sourceCode").value("TIMEOUT"));
    }
}
