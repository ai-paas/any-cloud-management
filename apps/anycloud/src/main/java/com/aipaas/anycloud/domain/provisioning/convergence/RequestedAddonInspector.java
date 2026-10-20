package com.aipaas.anycloud.domain.provisioning.convergence;

import com.aipaas.anycloud.domain.provisioning.VmClusterEntity;
import java.util.List;

public interface RequestedAddonInspector {

    /**
     * 프로비저닝 요청이 함축하는 addon 의 현재 설치 상태.
     *
     * <p>운영자가 이후 직접 추가한 addon 은 제외. 클러스터 생성 요청에 포함되지 않으므로 미설치가
     * DEGRADED 사유가 되면 안 됨. 예외 미전파.
     */
    List<ConvergenceSignal> inspect(VmClusterEntity vmCluster);
}
