package io.aipaas.cluster.provisioning.api;

import io.aipaas.cluster.provisioning.api.ProvisioningResult;
import io.aipaas.cluster.provisioning.api.exception.ProvisioningResultValidationException;
import io.aipaas.cluster.provisioning.api.ProvisioningRequest;
import io.aipaas.cluster.provisioning.api.ProvisioningPreview;
import java.util.Map;

/**
 * Pulumi 기반 cluster provisioning 오케스트레이션 — stack 준비 → config 적용 → up/preview/destroy.
 *
 * <p>stateless: ClusterEntity / Operation 영속화나 워크플로 상태 전이는 host(caller) 책임.
 * 자격증명은 {@link ProvisioningRequest#credentialEnvironmentOrEmpty()} 로 caller 가 inject.
 */
public interface ProvisioningService {

	String buildStackName(ProvisioningRequest request);

	Map<String, Object> provision(ProvisioningRequest request);

	/**
	 * Cluster create 사전 미리보기 — {@code pulumi preview --json}. CSP 자원 생성 없음.
	 * 신규 cluster 는 preview 용 stack 을 임시 생성 후 제거, 기존 stack 은 diff 만 반환.
	 */
	ProvisioningPreview preview(ProvisioningRequest request);

	Map<String, Object> stackOutputs(String stackName, boolean showSecrets);

	Map<String, Object> stackOutputs(String stackName, boolean showSecrets, Map<String, String> environmentOverrides);

	/**
	 * Pulumi raw output 을 {@link ProvisioningResult} record 로 매핑·검증해 반환.
	 * 표준 schema 위반 시 {@link ProvisioningResultValidationException} 발생.
	 */
	ProvisioningResult typedStackOutputs(String stackName, Map<String, String> environmentOverrides);

	void destroy(String stackName);

	void destroy(String stackName, Map<String, String> environmentOverrides);

	/**
	 * Pulumi {@code refresh} 등가 — CSP API 호출로 stack state 와 실 인프라 비교, drift 를 state 에 반영.
	 * 리소스 변경 없음 (read + state update). drift detection / state recovery 시 사용.
	 *
	 * @param request 현재 cluster 의 ProvisioningRequest (program function 실행에 필요).
	 *                 caller (anycloud admin) 는 VmClusterEntity.requestConfig 에서 restore.
	 */
	void refresh(ProvisioningRequest request);

	/**
	 * Stack state 파일 강제 제거 — CSP 자원은 정리 안 됨 (caller 책임). orphan state 정리용 admin 경로.
	 * destroy 와 다름: destroy 는 CSP 자원 + state 모두, removeStack 은 state 만.
	 *
	 * @param stackName             stack 이름 (provision 시 buildStackName 으로 생성된 그것).
	 * @param environmentOverrides  state backend 자격증명 등 (CSP 자격증명은 자동 strip).
	 */
	void removeStack(String stackName, Map<String, String> environmentOverrides);
}
