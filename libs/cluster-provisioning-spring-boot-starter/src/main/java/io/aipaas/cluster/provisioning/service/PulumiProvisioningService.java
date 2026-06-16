package io.aipaas.cluster.provisioning.service;

import io.aipaas.cluster.provisioning.core.ProvisioningOutput;
import io.aipaas.cluster.provisioning.core.ProvisioningOutputValidationException;
import io.aipaas.cluster.provisioning.core.ProvisioningRequest;
import io.aipaas.cluster.provisioning.core.PulumiPreviewResult;
import java.util.Map;

/**
 * Pulumi 기반 cluster provisioning 오케스트레이션 — stack 준비 → config 적용 → up/preview/destroy.
 *
 * <p>stateless: ClusterEntity / Operation 영속화나 워크플로 상태 전이는 host(caller)의 책임이다.
 * 자격증명은 {@link ProvisioningRequest#credentialEnvironmentOrEmpty()} 로 caller 가 inject 한다.
 */
public interface PulumiProvisioningService {

	String buildStackName(ProvisioningRequest request);

	Map<String, Object> provision(ProvisioningRequest request);

	/**
	 * Cluster create 사전 미리보기 — {@code pulumi preview --json}. CSP 자원 생성 없음.
	 * 신규 cluster 는 preview 용 stack 을 임시 생성 후 제거, 기존 stack 은 diff 만 반환.
	 */
	PulumiPreviewResult preview(ProvisioningRequest request);

	Map<String, Object> stackOutputs(String stackName, boolean showSecrets);

	Map<String, Object> stackOutputs(String stackName, boolean showSecrets, Map<String, String> environmentOverrides);

	/**
	 * Pulumi raw output 을 {@link ProvisioningOutput} record 로 매핑·검증해 반환한다.
	 * 표준 schema 위반 시 {@link ProvisioningOutputValidationException} 발생.
	 */
	ProvisioningOutput typedStackOutputs(String stackName, Map<String, String> environmentOverrides);

	void destroy(String stackName);

	void destroy(String stackName, Map<String, String> environmentOverrides);
}
