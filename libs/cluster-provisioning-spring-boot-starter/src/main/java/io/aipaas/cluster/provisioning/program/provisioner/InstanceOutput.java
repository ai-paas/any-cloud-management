package io.aipaas.cluster.provisioning.program.provisioner;

import com.pulumi.core.Output;
import com.pulumi.resources.Resource;

/**
 * InstanceProvisioner 가 반환하는 정규화 instance 결과.
 *
 * @param resource  생성된 native Pulumi resource — 후속 instance 가 DependsOn 으로 사용 (예: worker
 *                  들이 master 의 join 토큰 준비를 기다림).
 * @param instanceId 인스턴스 식별자 (CSP 별 형식).
 * @param privateIp  VPC 내부 IP.
 * @param publicIp   외부 IP. 외부 IP 없는 경우 빈 문자열 Output.
 */
public record InstanceOutput(
        Resource resource,
        Output<String> instanceId,
        Output<String> privateIp,
        Output<String> publicIp) {}
