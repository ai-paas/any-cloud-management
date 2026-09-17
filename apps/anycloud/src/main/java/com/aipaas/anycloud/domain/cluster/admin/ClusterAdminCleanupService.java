package com.aipaas.anycloud.domain.cluster.admin;

import java.util.Map;

/** Admin force-delete + Pulumi orphan stack 정리 서비스. */
public interface ClusterAdminCleanupService {

    /**
     * 일반 DELETE 가 막힌 cluster (cred mismatch / stale state) 의 DB row 만 purge.
     *
     * @param clusterName force-delete 대상 cluster
     * @return 결과 정보 (stackName / priorStatus / warning 등) — controller 가 response 로 노출
     * @throws com.aipaas.anycloud.common.error.exception.ClusterNotFoundException cluster row 없음
     */
    Map<String, Object> forceDelete(String clusterName);

    /**
     * RustFS 의 orphan Pulumi stack file 만 정리. DB 는 손대지 않음.
     *
     * @param stackName Pulumi stack 식별자
     * @return success / exitCode 등 결과 — controller 가 response 로 노출
     */
    Map<String, Object> cleanupOrphanState(String stackName);
}
