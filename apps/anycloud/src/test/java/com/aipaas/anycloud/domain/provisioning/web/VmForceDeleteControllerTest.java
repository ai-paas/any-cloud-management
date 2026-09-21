package com.aipaas.anycloud.domain.provisioning.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.aipaas.anycloud.common.error.handler.GlobalExceptionHandler;
import com.aipaas.anycloud.domain.cluster.ClusterFacade;
import com.aipaas.anycloud.domain.cluster.kubeconfig.KubeconfigExportService;
import com.aipaas.anycloud.domain.cluster.kubeconfig.KubeconfigIdentityResolver;
import com.aipaas.anycloud.domain.operation.OperationService;
import com.aipaas.anycloud.domain.provisioning.VmClusterStateHistoryQueryService;
import com.aipaas.anycloud.domain.provisioning.command.ForceDeleteResult;
import com.aipaas.anycloud.domain.provisioning.command.VmClusterCommandService;
import com.aipaas.anycloud.domain.provisioning.convergence.internal.ClusterComponentRepairFacade;
import com.aipaas.anycloud.domain.provisioning.query.VmClusterQueryService;
import com.aipaas.anycloud.domain.provisioning.remote.VmClusterSshAccessService;
import com.aipaas.anycloud.testsupport.AbstractUnitTest;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/** 강제 삭제 응답의 숫자가 뒤바뀌면 사용자는 몇 건이 지워졌는지 모른다. */
class VmForceDeleteControllerTest extends AbstractUnitTest {

    @Mock
    ClusterFacade clusterFacade;

    @Mock
    VmClusterQueryService vmClusterQueryService;

    @Mock
    OperationService operationService;

    @Mock
    VmClusterStateHistoryQueryService stateHistoryQueryService;

    @Mock
    VmClusterSshAccessService vmClusterSshAccessService;

    @Mock
    VmClusterCommandService vmClusterCommandService;

    @Mock
    KubeconfigExportService kubeconfigExportService;

    @Mock
    KubeconfigIdentityResolver kubeconfigIdentityResolver;

    @Mock
    ClusterComponentRepairFacade componentRepairFacade;

    @Mock
    com.aipaas.anycloud.domain.provisioning.VmClusterService vmClusterService;

    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.standaloneSetup(new VmController(
                        clusterFacade,
                        vmClusterQueryService,
                        vmClusterService,
                        operationService,
                        stateHistoryQueryService,
                        vmClusterSshAccessService,
                        vmClusterCommandService,
                        kubeconfigExportService,
                        kubeconfigIdentityResolver,
                        componentRepairFacade))
                .setControllerAdvice(new GlobalExceptionHandler(new ObjectMapper()))
                .build();
    }

    @Test
    void reportsRemovedRecordsNotStackCount() throws Exception {
        when(vmClusterCommandService.forceDeleteVmCluster("demo"))
                .thenReturn(new ForceDeleteResult(3, List.of("stack-1")));

        mvc.perform(delete("/v1/vms/demo").param("force", "true"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.removedRecords").value(3))
                .andExpect(jsonPath("$.data.orphanedStacks[0]").value("stack-1"))
                .andExpect(jsonPath("$.data.warning").isNotEmpty());

        verify(clusterFacade, never()).deleteDomain(any());
    }

    @Test
    void plainDeleteStillRunsDestroy() throws Exception {
        when(clusterFacade.deleteDomain("demo")).thenReturn(null);

        mvc.perform(delete("/v1/vms/demo"));

        verify(clusterFacade).deleteDomain("demo");
        verify(vmClusterCommandService, never()).forceDeleteVmCluster(any());
    }
}
