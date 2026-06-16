package com.aipaas.anycloud.domain.provisioning.bootstrap;

import com.aipaas.anycloud.domain.provisioning.VmClusterEntity;
import java.util.Map;

public interface VmClusterBootstrapLogService {

    /**
     * Master 노드에서 cloud-init / kubelet / kubeadm / kubectl 진단 로그 수집.
     * Append 시 caller 가 호출 (호출 단독으로 entity 에 set 하지 않음).
     */
    String collectBootstrapLog(VmClusterEntity vmCluster, Map<String, Object> outputs);

    /**
     * 새 bootstrap attempt 시작 시 기존 log 위에 attempt 마커 추가.
     * Retry log 가 사라지지 않도록 append 방식 — 디버깅 가시성 보존.
     * 마커 형식: {@code === attempt N — yyyy-MM-dd HH:mm:ss ===}.
     * 총 길이 cap 초과 시 가장 오래된 attempt 부터 잘라낸다.
     *
     * @param attemptNumber 1-based attempt index (workflow retry count + 1).
     */
    void appendAttemptMarker(VmClusterEntity vmCluster, int attemptNumber);

    /**
     * Bootstrap 실패 진단 시점에 호출 — {@link #collectBootstrapLog} 결과를 기존 log 끝에
     * append. 마커는 caller 가 {@link #appendAttemptMarker} 로 이미 추가한 상태여야 한다.
     */
    void appendDiagnostics(VmClusterEntity vmCluster, Map<String, Object> outputs);
}
