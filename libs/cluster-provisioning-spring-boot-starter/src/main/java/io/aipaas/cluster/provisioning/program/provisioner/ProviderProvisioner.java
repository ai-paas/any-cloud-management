package io.aipaas.cluster.provisioning.program.provisioner;

import com.pulumi.Context;
import com.pulumi.core.Output;
import io.aipaas.cluster.provisioning.program.ClusterSpec;
import java.util.Map;

/**
 * 한 CSP 의 cluster provisioning 책임. Go {@code infra/pulumi/pkg/providers/provider.go::ClusterProvisioner}
 * 등가물.
 *
 * <p>{@code provision(...)} 가 호출되면 구현체는 자신의 CSP API 로 VPC/subnet/SG/instances 를 만들고,
 * cluster 의 정규화된 outputs map 을 반환. caller (ProvisionerOrchestrator) 는 outputs 를 ctx.export(...) 로
 * stack output 으로 노출.
 *
 * <p>외부 output schema (key 이름) 은 모든 provider 가 동일 — caller (anycloud) 가 provider 별 분기 없이
 * 통일된 ProvisioningResult POJO 로 매핑 가능. 표준 키:
 *
 * <ul>
 *   <li>{@code provider, clusterName, masterVmSpec, workerVmSpec, osImage}
 *   <li>{@code vpcId, masterInstanceId, masterPublicIp, masterPrivateIp, masterPublicDns}
 *   <li>{@code apiServerUrl} (예: "https://&lt;master-ip&gt;:6443")
 *   <li>{@code sshPrivateKeyPem} (secret), {@code masterSshCommand} (secret)
 *   <li>{@code kubeconfigRemotePath, kubeconfigFetchCommand} (secret)
 *   <li>{@code nodes} (array of {role, instanceId, publicIp, privateIp, publicDns, ssh})
 *   <li>DB 활성 시: {@code dbEndpoint, dbName, dbUsername}
 * </ul>
 */
public interface ProviderProvisioner {

    /** Canonical provider 토큰 — "aws", "gcp", "azure" 등. */
    String name();

    /**
     * Cluster 의 모든 resource 생성 + outputs 빌드.
     *
     * @return cloud-agnostic output map. value 는 Pulumi {@code Output<?>} 또는 String/Number/Boolean/List/Map.
     */
    Map<String, Output<?>> provision(Context ctx, ClusterSpec spec);
}
