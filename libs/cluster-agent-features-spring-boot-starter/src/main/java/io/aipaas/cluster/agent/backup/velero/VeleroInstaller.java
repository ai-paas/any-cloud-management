package io.aipaas.cluster.agent.backup.velero;

/** Velero helm install. */
public interface VeleroInstaller {

    /**
     * Velero 설치. 이미 설치된 cluster 에 다시 호출하면 helm upgrade-or-install 동작 (helm 의 기본).
     *
     * @param clusterName 대상 cluster
     * @param spec        BSL/VSL/credentials 채워진 spec
     * @return install 결과
     * @throws io.aipaas.cluster.agent.backup.core.BackupException 실패 시 (error code 로 분기)
     */
    VeleroInstallResult install(String clusterName, VeleroInstallSpec spec);
}
