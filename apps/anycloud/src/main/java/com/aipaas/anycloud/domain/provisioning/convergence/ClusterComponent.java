package com.aipaas.anycloud.domain.provisioning.convergence;

import com.aipaas.anycloud.domain.provisioning.VmClusterEntity;
import com.aipaas.anycloud.domain.provisioning.model.VmClusterInternalRequestSnapshot;
import java.util.Map;

/**
 * VM 위에 올라가는 한 계층의 desired state 와 observed state.
 *
 * <p>probe 가 apply 와 분리되는 것이 계약의 핵심이다. "설치 명령을 실행했다" 는 "설치되었다" 가
 * 아니다 — 실패를 무시하는 셸 스크립트에서 그 등식이 깨졌다.
 */
public interface ClusterComponent {

    ComponentType type();

    /** 요청 스냅샷에 비추어 이 컴포넌트가 필요한지. 스냅샷 필드는 과거 요청 탓에 null 일 수 있다. */
    Requirement requirementFor(VmClusterInternalRequestSnapshot spec);

    /**
     * 실제로 동작 중인지 관측. apply 없이 단독 호출 가능해야 한다.
     *
     * <p>구현체는 예외를 던지지 않는다 — transport 실패는 {@link ComponentProbe#unknown} 으로
     * 표현한다. 조정 루프가 컴포넌트 하나 때문에 중단되면 안 된다.
     */
    ComponentProbe probe(VmClusterEntity cluster, Map<String, Object> outputs);
}
