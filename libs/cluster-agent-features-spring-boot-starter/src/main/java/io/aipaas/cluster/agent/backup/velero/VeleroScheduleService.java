package io.aipaas.cluster.agent.backup.velero;

/** Velero Schedule CR 생성 service. */
public interface VeleroScheduleService {

    VeleroCrResult create(String clusterName, VeleroScheduleRequest request);
}
