package io.aipaas.cluster.agent.backup.velero;

/** Velero Backup CR 생성 service. */
public interface VeleroBackupService {

    VeleroCrResult create(String clusterName, VeleroBackupRequest request);
}
