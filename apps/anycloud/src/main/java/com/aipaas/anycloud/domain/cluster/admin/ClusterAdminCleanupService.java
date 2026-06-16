package com.aipaas.anycloud.domain.cluster.admin;

import java.util.Map;

/**
 * Admin force-delete + Pulumi orphan stack 정리 서비스.
 *
 * <p>컨트롤러가 Repository 를 직접 inject 하면 계층 위반이므로 service 로 추출.
 * 컨트롤러는 본 service 만 호출한다.
 */
public interface ClusterAdminCleanupService {

    /**
     * 일반 DELETE 가 막힌 cluster (cred mismatch / stale state) 의 DB row 만 purge.
     *
     * <p>Pulumi state file (RustFS) 과 CSP 자원 (VPC/EC2 등) 은 운영자가 별도로 정리해야 한다 —
     * 응답 result 의 warning 필드에 명시.
     *
     * @param clusterName force-delete 대상 cluster
     * @return 결과 정보 (stackName / priorStatus / warning 등) — controller 가 response 로 노출
     * @throws com.aipaas.anycloud.common.error.exception.ClusterNotFoundException cluster row 없음
     */
    Map<String, Object> forceDelete(String clusterName);

    /**
     * RustFS 의 orphan Pulumi stack file 만 정리. DB 는 손대지 않음.
     *
     * <p>force-delete 후 stale state file 이 남아 같은 stackName 으로 새 cluster create 가
     * 충돌하는 시나리오에 사용. {@code pulumi stack rm --force --yes} 호출.
     *
     * @param stackName Pulumi stack 식별자
     * @return success / exitCode 등 결과 — controller 가 response 로 노출
     */
    Map<String, Object> cleanupOrphanState(String stackName);
}
