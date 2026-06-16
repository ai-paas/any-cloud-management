package io.aipaas.cluster.agent.backup.velero;

/** Velero Restore CR 생성 service. */
public interface VeleroRestoreService {

	VeleroCrResult create(String clusterName, VeleroRestoreRequest request);
}
