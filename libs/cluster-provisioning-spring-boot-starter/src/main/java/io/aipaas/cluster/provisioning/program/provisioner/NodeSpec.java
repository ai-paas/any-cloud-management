package io.aipaas.cluster.provisioning.program.provisioner;

import com.pulumi.core.Output;

/**
 * 단일 node (master 또는 worker) 의 정규화 사양 — provider-agnostic.
 *
 * <p>caller 가 ClusterSpec 으로부터 master/worker 별 NodeSpec list 를 빌드해 ProviderProvisioner 에게
 * 전달. provider 구현은 InstanceType / OsImage / UseSpot 을 자신의 SDK 호출로 번역만 하OK.
 *
 * @param role          master 또는 worker
 * @param index         0-based — master-1 → 0
 * @param instanceType  CSP 별 형식. AWS "t3.medium", GCP "e2-standard-4", Azure "Standard_D2s_v3"
 * @param osImage       빈 문자열이면 provider default. AMI ID / Image URN / family name 등 CSP 별.
 * @param useSpot       master 는 항상 false 강제 (control-plane stability). worker 만 spec 따름.
 * @param userData      cloud-init bash. Pulumi Output 으로 lazy 평가.
 * @param subnetIndex   network.subnetIds[i] 에서 i — zone 분산용.
 * @param rootDiskSizeGb root(boot) disk GB. 0 이면 provider default — Defaults 가 50 으로 정규화.
 */
public record NodeSpec(
        InstanceRole role,
        int index,
        String instanceType,
        String osImage,
        boolean useSpot,
        Output<String> userData,
        int subnetIndex,
        int rootDiskSizeGb) {}
