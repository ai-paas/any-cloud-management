package com.aipaas.anycloud.domain.provisioning.preflight;

import com.aipaas.anycloud.domain.provisioning.api.request.ProvisionClusterRequest;
import com.aipaas.anycloud.domain.provisioning.api.response.VmClusterPreflightResponse;

/**
 * VM cluster 생성 전 사전 검증 — provider / credential / VM options discovery / readiness / cost
 * estimation 을 한 번에 평가해 frontend 에 ready-to-provision 여부 응답.
 *
 * <p>{@link VmClusterQueryServiceImpl} 의 449 LOC god class 에서 분리.
 * 의도적 boundary: read-only query (list / status) 와 heavy validation (preflight) 의 책임 분리.
 *
 * <p>호출 path: REST `POST /v1/vm-clusters/preflight` → {@link VmClusterQueryService} (interface
 * 보존, internal delegation) → 본 service. caller 측 변경 0.
 */
public interface VmClusterPreflightService {

    /**
     * 사전 검증 실행 — provider/credential/options/cost 종합 평가.
     *
     * @param cluster 사용자 입력 ProvisionClusterRequest
     * @return preflight response (readyToProvision / errors / warnings / costEstimate / checklist)
     */
    VmClusterPreflightResponse preflightVmCluster(ProvisionClusterRequest cluster);

    /**
     * Pulumi preview 기반 create 미리보기 — 실제 생성될 CSP resource 계획 반환.
     * preflight (정적 검증, ~ms) 와 달리 Pulumi CLI + CSP API 를 실제 호출 (수십 초).
     */
    com.aipaas.anycloud.domain.provisioning.api.response.VmClusterPreviewResponse previewVmCluster(
            ProvisionClusterRequest cluster);
}
