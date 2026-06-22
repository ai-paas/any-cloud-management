package com.aipaas.anycloud.domain.vmoptions.internal;

import com.aipaas.anycloud.common.error.enums.ErrorCode;
import com.aipaas.anycloud.common.error.exception.CustomException;
import com.aipaas.anycloud.domain.provisioning.model.SupportedProvisioningProvider;
import com.aipaas.anycloud.domain.vmoptions.ProviderConfigSchemaService;
import com.aipaas.anycloud.domain.vmoptions.api.ProviderConfigKey;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Service;

/**
 * {@link ProviderConfigSchemaService} 의 정적 catalog 구현. {@code ProvisioningConfigRules}
 * 의 검증 로직과 정합성 유지 — 변경 시 동기 갱신 필요.
 *
 * <p>HA / boolean strict / masterCount validation 등 UX 개선과 함께 추가됨.
 */
@Service
public class ProviderConfigSchemaServiceImpl implements ProviderConfigSchemaService {

    @Override
    public List<ProviderConfigKey> getSchema(String provider) {
        SupportedProvisioningProvider p;
        try {
            p = SupportedProvisioningProvider.from(provider);
        } catch (IllegalArgumentException e) {
            throw new CustomException(
                    "Unsupported provider: " + provider
                            + ". 지원: AWS, GCP, Azure, Alibaba, OpenStack, OCI, DigitalOcean",
                    ErrorCode.PROVISIONING_PROVIDER_UNSUPPORTED);
        }
        List<ProviderConfigKey> schema = new ArrayList<>(commonKeys(p));
        schema.addAll(providerSpecificKeys(p));
        return List.copyOf(schema);
    }

    private List<ProviderConfigKey> commonKeys(SupportedProvisioningProvider provider) {
        return List.of(
                ProviderConfigKey.builder()
                        .key("anycloud-k8s:masterInstanceType")
                        .type("string")
                        .required(false)
                        .defaultValue(defaultMasterSpec(provider))
                        .description("Master 노드 VM spec (instance type / flavor). provider 마다 형식 다름.")
                        .build(),
                ProviderConfigKey.builder()
                        .key("anycloud-k8s:workerInstanceType")
                        .type("string")
                        .required(false)
                        .defaultValue(defaultMasterSpec(provider))
                        .description("Worker 노드 VM spec.")
                        .build(),
                ProviderConfigKey.builder()
                        .key("anycloud-k8s:masterCount")
                        .type("integer")
                        .required(false)
                        .defaultValue("1")
                        .description("Control-plane 노드 수. 1=single, 3/5/7=HA. odd-only (etcd quorum).")
                        .allowedValues(List.of("1", "3", "5", "7"))
                        .build(),
                ProviderConfigKey.builder()
                        .key("anycloud-k8s:workerCount")
                        .type("integer")
                        .required(false)
                        .defaultValue("2")
                        .description("Worker 노드 수. 1~50.")
                        .allowedValues(List.of("1..50"))
                        .build(),
                ProviderConfigKey.builder()
                        .key("anycloud-k8s:kubernetesVersion")
                        .type("string")
                        .required(false)
                        .defaultValue("1.31")
                        .description("Kubernetes 버전. N.N 또는 N.N.N-suffix.")
                        .build(),
                ProviderConfigKey.builder()
                        .key("anycloud-k8s:podCidr")
                        .type("cidr")
                        .required(false)
                        .defaultValue("192.168.0.0/16")
                        .description("Pod 네트워크 CIDR.")
                        .build(),
                ProviderConfigKey.builder()
                        .key("anycloud-k8s:serviceCidr")
                        .type("cidr")
                        .required(false)
                        .defaultValue("10.96.0.0/12")
                        .description("Service 네트워크 CIDR.")
                        .build(),
                ProviderConfigKey.builder()
                        .key("anycloud-k8s:joinToken")
                        .type("string")
                        .required(false)
                        .description("Kubeadm join token — backend 가 cluster 별 random 생성. "
                                + "사용자 입력은 무시됨 (보안: 공유/약한 token 차단).")
                        .build(),
                ProviderConfigKey.builder()
                        .key("anycloud-k8s:enableIngress")
                        .type("boolean")
                        .required(false)
                        .defaultValue("false")
                        .description("Nginx Ingress Controller 자동 설치. true/false strict (case-insensitive).")
                        .allowedValues(List.of("true", "false"))
                        .build(),
                ProviderConfigKey.builder()
                        .key("anycloud-k8s:enableGpuOperator")
                        .type("boolean")
                        .required(false)
                        .defaultValue("false")
                        .description("NVIDIA GPU Operator 자동 설치. Ubuntu 계열만 driver 자동 설치.")
                        .allowedValues(List.of("true", "false"))
                        .build(),
                ProviderConfigKey.builder()
                        .key("anycloud-k8s:dbEnabled")
                        .type("boolean")
                        .required(false)
                        .defaultValue(null)
                        .description("외부 데이터베이스 인스턴스 생성 (provider 별 RDS/SQL 등). 기본 disabled.")
                        .allowedValues(List.of("true", "false"))
                        .build());
    }

    private List<ProviderConfigKey> providerSpecificKeys(SupportedProvisioningProvider provider) {
        return switch (provider) {
            case GCP -> List.of(
                    ProviderConfigKey.builder()
                            .key("anycloud-k8s:gcpProject")
                            .type("string")
                            .required(true)
                            .description("GCP project ID.")
                            .build(),
                    osImage("anycloud-k8s:gcpImage", "GCP image family (예: ubuntu-2404-lts)."));
            case AZURE -> List.of(
                    ProviderConfigKey.builder()
                            .key("anycloud-k8s:azureResourceGroup")
                            .type("string")
                            .required(true)
                            .description("Azure resource group 이름.")
                            .build(),
                    osImage("anycloud-k8s:azureImage", "Azure image URN."));
            case OPENSTACK -> List.of(
                    ProviderConfigKey.builder()
                            .key("anycloud-k8s:openstackImageName")
                            .type("string")
                            .required(true)
                            .defaultValue("ubuntu-24.04")
                            .description("OpenStack glance image 이름.")
                            .build(),
                    ProviderConfigKey.builder()
                            .key("anycloud-k8s:openstackFlavorName")
                            .type("string")
                            .required(true)
                            .defaultValue("m1.large")
                            .description("OpenStack flavor 이름.")
                            .build(),
                    ProviderConfigKey.builder()
                            .key("anycloud-k8s:openstackExternalNetworkId")
                            .type("string")
                            .required(false)
                            .description("External network ID (floating IP 발급용). FloatingIpPool 과 하나 필수.")
                            .build(),
                    ProviderConfigKey.builder()
                            .key("anycloud-k8s:openstackFloatingIpPool")
                            .type("string")
                            .required(false)
                            .description("Floating IP pool 이름. ExternalNetworkId 와 하나 필수.")
                            .build());
            case OCI -> List.of(ProviderConfigKey.builder()
                    .key("anycloud-k8s:ociCompartmentId")
                    .type("string")
                    .required(true)
                    .description("OCI compartment OCID.")
                    .build());
            case AWS -> List.of(
                    osImage("anycloud-k8s:awsImageName", "AWS AMI 이름 또는 ID (예: ubuntu-jammy-22.04-amd64)."));
            case ALIBABA, DIGITALOCEAN -> List.of();
        };
    }

    private ProviderConfigKey osImage(String key, String description) {
        return ProviderConfigKey.builder()
                .key(key)
                .type("string")
                .required(false)
                .description(description)
                .build();
    }

    private String defaultMasterSpec(SupportedProvisioningProvider provider) {
        return switch (provider) {
            case GCP -> "e2-standard-2";
            case AZURE -> "Standard_D4s_v5";
            case ALIBABA -> "ecs.g6.large";
            case OPENSTACK -> "m1.large";
            case OCI -> "VM.Standard.E4.Flex";
            case DIGITALOCEAN -> "s-2vcpu-4gb";
            case AWS -> "t3.large";
        };
    }
}
