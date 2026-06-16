package com.aipaas.anycloud.domain.provisioning.admin;

import java.util.Map;

/**
 * Pulumi state drift 감지 / refresh 서비스.
 *
 * <p>컨트롤러는 dispatcher 책임만, 도메인 로직 (VmClusterRepository 조회 + Pulumi 명령 위임) 은
 * 본 서비스에서 처리한다.
 *
 * <p>Drift = Pulumi state 와 실제 CSP 자원의 불일치. 운영자가 CSP 콘솔에서 VM 을 직접 변경/삭제하면
 * 발생하며 이후 scale / destroy 가 stale state 기준으로 동작해 실패할 수 있다.
 */
public interface VmClusterDriftService {

    /**
     * {@code pulumi preview --refresh --json} 으로 read-only drift 감지.
     *
     * <p>CSP API 호출이 포함되어 수십 초가 걸릴 수 있다. 응답에는 drifted (boolean) /
     * changeSummary / 변경 step 목록이 포함.
     *
     * @param clusterName 대상 cluster
     * @return drifted / changeSummary / steps 등 — controller 가 그대로 response 로 노출
     * @throws com.aipaas.anycloud.common.error.exception.ClusterNotFoundException cluster 없음 / stack 없음
     * @throws com.aipaas.anycloud.common.error.exception.provisioning.PulumiExecutionException Pulumi CLI 호출 실패 (502 UPSTREAM_FAILED)
     */
    Map<String, Object> detectDrift(String clusterName);

    /**
     * {@code pulumi refresh --yes} 로 state file 을 실제 CSP 상태와 동기화. CSP 자원 자체는 만들지도
     * 지우지도 않는다 — state file 만 갱신.
     *
     * @param clusterName 대상 cluster
     * @return success / exitCode / stackName 등 — controller 가 그대로 response 로 노출
     */
    Map<String, Object> refreshState(String clusterName);
}
