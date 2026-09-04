package io.aipaas.cluster.provisioning.program.yaml;

import io.aipaas.cluster.provisioning.program.ClusterSpec;

/**
 * CSP 별 YAML 리소스 정의 생성.
 *
 * <p>public 이 아니다. {@code ProviderProvisioner} 확장점을 닫기로 했으므로 외부 소비자는
 * {@code ProvisioningService} 만 쓴다. CSP 지원은 이 starter 가 책임진다.
 */
interface ProviderYamlEmitter {

    /** canonical provider 토큰. {@code ProviderName.canonical} 과 같은 값. */
    String name();

    /**
     * 리소스를 builder 에 추가하고, 출력 조립에 필요한 참조를 돌려준다.
     *
     * @throws IllegalStateException 필수 config 가 없으면 — preview 까지 가지 말고 즉시 실패한다
     */
    StandardOutputs.NodeRefs emit(PulumiProgram.Builder builder, ClusterSpec spec);
}
