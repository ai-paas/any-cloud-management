package com.aipaas.anycloud.domain.provisioning.convergence;

import com.aipaas.anycloud.domain.provisioning.VmClusterEntity;
import java.util.List;

public interface RequestedAddonInspector {

    /**
     * 프로비저닝 요청이 함축하는 addon 의 현재 설치 상태.
     *
     * <p>운영자가 나중에 직접 추가한 addon 은 포함하지 않는다 — 클러스터 생성 요청의 일부가 아니므로
     * 미설치가 DEGRADED 사유가 되면 안 된다. 예외를 던지지 않는다.
     */
    List<ConvergenceSignal> inspect(VmClusterEntity vmCluster);
}
