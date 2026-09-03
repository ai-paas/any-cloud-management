package io.aipaas.cluster.provisioning.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.List;
import java.util.Map;

/**
 * 모든 Pulumi provider 가 export 해야 하는 표준 출력 계약 (청사진 §4).
 *
 * <p>모든 backend consumer 가 동일 schema 사용.
 *
 * <p>Map&lt;String, Object&gt; raw output 을 강타입 record 로 매핑하여 jakarta.validation 으로 schema
 * 정합성을 강제. 새 provider 가 누락된 필드를 export 하면 ProvisioningResultMapper (host-side)
 * 가 부팅/실행 시점에 {@link ProvisioningResultValidationException} 을 던져 즉시 인지.
 *
 * <p>7 CSP 모두에 공통인 키만 record 필드로 두고, provider 별 추가 필드는 {@code providerNative} Map 에
 * 격리하여 호환성을 보존.
 *
 * @param provider          정규화된 provider 이름 (예: aws, gcp, azure, openstack, ...)
 * @param clusterName       사용자가 부여한 클러스터 이름
 * @param apiServerUrl      kubeadm control-plane 의 https 엔드포인트 (port 6443)
 * @param masterPublicIp    master 의 public IP. 사설망 only 환경에서는 빈 문자열 허용 → NotBlank 제외
 * @param masterPrivateIp   master 의 private IP. 모든 provider 가 필수
 * @param sshPrivateKeyPem  Bootstrap Worker 가 SSH/SCP 로 접속할 PEM. Pulumi 의 secret 으로 표시됨
 * @param kubeconfigFetchCommand SCP 또는 ssh cat 으로 kubeconfig 를 가져오는 명령 문자열
 * @param nodes             master/worker 노드 정보. provider 별 필드 다양성이 커 raw Map 보존
 * @param publicDns         AWS 등 일부 provider 만 채움. 그 외 빈 문자열 또는 null
 * @param dbEndpoint        AWS RDS 동시 생성 시 채움. 그 외 null
 * @param providerNative    provider 별 추가 필드의 자유 영역. 표준 키 외 raw 값을 모두 보존
 */
public record ProvisioningResult(

		@NotBlank String provider,

		@NotBlank String clusterName,

		@NotBlank String apiServerUrl,

		// 사설망 only 환경은 빈 문자열 — NotBlank 제외
		String masterPublicIp,

		@NotBlank String masterPrivateIp,

		@NotBlank String sshPrivateKeyPem,

		@NotBlank String kubeconfigFetchCommand,

		@NotNull List<Map<String, Object>> nodes,

		String publicDns,

		String dbEndpoint,

		@NotNull Map<String, Object> providerNative) {
}
