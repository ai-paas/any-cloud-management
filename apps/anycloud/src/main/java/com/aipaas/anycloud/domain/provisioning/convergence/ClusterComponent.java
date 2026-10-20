package com.aipaas.anycloud.domain.provisioning.convergence;

import com.aipaas.anycloud.domain.provisioning.VmClusterEntity;
import com.aipaas.anycloud.domain.provisioning.model.VmClusterInternalRequestSnapshot;
import java.util.Map;
import java.util.function.Supplier;

/**
 * VM 위에 올라가는 한 계층의 desired state 와 observed state.
 *
 * <p>probe 와 apply 의 분리가 계약의 핵심. 설치 명령 실행과 설치 완료는 별개이며, 실패를
 * 무시하는 셸 스크립트에서 두 값이 어긋남.
 *
 * <p>addon 으로 설치되는 항목은 대상 외. {@code AddonInstaller} 와 {@code AddonState} 가 동일
 * 역할을 수행하며, 이중화 시 버전과 values 가 분리.
 */
public interface ClusterComponent {

    ComponentType type();

    /** 요청 스냅샷 기준 컴포넌트 필요 여부. 과거 요청으로 생성된 스냅샷 필드는 null 가능. */
    Requirement requirementFor(VmClusterInternalRequestSnapshot spec);

    /**
     * 동작 여부 관측. apply 없이 단독 호출 가능.
     *
     * <p>구현체는 예외 미발생. transport 실패는 {@link ComponentProbe#unknown} 으로 표현.
     * 컴포넌트 하나의 실패로 조정 루프 중단 금지.
     *
     * <p>{@code outputs} 가 Supplier 인 이유: 값 조회에 Pulumi 호출 필요. 5분 주기 조정이 전체
     * 클러스터를 순회하므로 미사용 구현까지 값을 받으면 비용이 클러스터 수에 비례. 필요한
     * 구현만 {@code get()} 호출.
     */
    void apply(VmClusterEntity cluster, Supplier<Map<String, Object>> outputs);

    ComponentProbe probe(VmClusterEntity cluster, Supplier<Map<String, Object>> outputs);
}
