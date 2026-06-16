package com.aipaas.anycloud.domain.provisioning.workflow;

import com.aipaas.anycloud.domain.provisioning.api.response.VmClusterWorkflowQueueResponse;
import java.util.List;

public interface VmClusterWorkflowQueueService {

    List<VmClusterWorkflowQueueResponse> getWorkflowQueues();
}
