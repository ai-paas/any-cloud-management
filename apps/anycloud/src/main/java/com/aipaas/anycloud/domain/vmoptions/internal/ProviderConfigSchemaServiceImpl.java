package com.aipaas.anycloud.domain.vmoptions.internal;

import com.aipaas.anycloud.common.error.enums.ErrorCode;
import com.aipaas.anycloud.common.error.exception.CustomException;
import com.aipaas.anycloud.domain.provisioning.model.SupportedProvisioningProvider;
import com.aipaas.anycloud.domain.vmoptions.ProviderConfigSchemaService;
import com.aipaas.anycloud.domain.vmoptions.api.ProviderConfigKey;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Service;

/** {@link ProviderConfigSchemaService} 의 정적 catalog 구현. {@code ProvisioningConfigRules} 의 검증 로직과 정합성 유지 — 변경 시 동기 갱신 필요. */
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
                        .defaultValue("10.244.0.0/16")
                        .description("Pod 네트워크 CIDR. 노드가 속한 사설망과 겹치면 파드 egress 가 끊긴다.")
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
                            .key("anycloud-k8s:providerSpec.project")
                            .type("string")
                            .required(true)
                            .label("프로젝트")
                            .description("자원을 만들 GCP 프로젝트. 자격증명이 속한 프로젝트가 목록에 뜬다.")
                            .build(),
                    osImage("anycloud-k8s:gcpImage", "GCP image family (예: ubuntu-2404-lts)."));
            case PROXMOX -> List.of(
                    ProviderConfigKey.builder()
                            .key("anycloud-k8s:providerSpec.nodeName")
                            .type("string")
                            .required(true)
                            .label("PVE 노드")
                            .description("VM 을 올릴 물리 노드. PVE 가 클러스터로 묶여 있어도 한 대를 지정해야 한다.")
                            .build(),
                    ProviderConfigKey.builder()
                            .key("anycloud-k8s:providerSpec.datastoreId")
                            .type("string")
                            .required(false)
                            .defaultValue("local-lvm")
                            .label("디스크 저장소")
                            .description("VM 디스크를 만들 저장소. 블록 저장소를 고른다 (보통 local-lvm).")
                            .build(),
                    ProviderConfigKey.builder()
                            .key("anycloud-k8s:providerSpec.imageDatastoreId")
                            .type("string")
                            .required(false)
                            .defaultValue("local")
                            .label("이미지 저장소")
                            .description("내려받은 OS 이미지를 둘 저장소. import 콘텐츠를 받는 디렉터리 저장소만 쓸 수 있다.")
                            .build(),
                    ProviderConfigKey.builder()
                            .key("anycloud-k8s:providerSpec.networkBridge")
                            .type("string")
                            .required(false)
                            .defaultValue("vmbr0")
                            .label("네트워크 브리지")
                            .description("VM 을 붙일 브리지. 외부와 통신하는 브리지를 고른다 (보통 vmbr0).")
                            .build());
            case IBM -> List.of(
                    ProviderConfigKey.builder()
                            .key("anycloud-k8s:providerSpec.zone")
                            .type("string")
                            .required(true)
                            .label("존")
                            .description("리전 안에서 실제로 VM 이 올라갈 구역. 계정마다 쓸 수 있는 존이 다르다.")
                            .build(),
                    ProviderConfigKey.builder()
                            .key("anycloud-k8s:providerSpec.resourceGroup")
                            .type("string")
                            .required(false)
                            .label("리소스 그룹")
                            .description("자원을 묶을 그룹. 비우면 계정 기본 그룹을 쓴다.")
                            .build(),
                    ProviderConfigKey.builder()
                            .key("anycloud-k8s:osImage")
                            .type("string")
                            .required(true)
                            .label("OS 이미지")
                            .description("이름에 빌드 번호가 붙어 주기적으로 갈린다. 목록에서 고른다.")
                            .build());
            case OCI -> List.of(
                    ProviderConfigKey.builder()
                            .key("anycloud-k8s:providerSpec.compartmentId")
                            .type("string")
                            .required(true)
                            .label("컴파트먼트")
                            .description("자원을 담을 칸. 테넌시 전체를 쓰려면 맨 위 항목을 고른다.")
                            .build(),
                    // 이미지 OCID 는 리전마다 따로 발급돼 추측할 수 없다. emitter 도 preflight 도 요구한다.
                    ProviderConfigKey.builder()
                            .key("anycloud-k8s:osImage")
                            .type("string")
                            .required(true)
                            .label("OS 이미지")
                            .description("이미지 식별자가 리전마다 따로 발급돼 추측할 수 없다. 목록에서 고른다.")
                            .build());
            case OPENSTACK -> List.of(
                    ProviderConfigKey.builder()
                            .key("anycloud-k8s:providerSpec.imageName")
                            .type("string")
                            .required(true)
                            .defaultValue("ubuntu-24.04")
                            .label("OS 이미지")
                            .description("노드에 올릴 이미지. 부트스트랩이 Ubuntu 를 전제하므로 Ubuntu 를 고른다.")
                            .build(),
                    ProviderConfigKey.builder()
                            .key("anycloud-k8s:providerSpec.flavorName")
                            .type("string")
                            .required(true)
                            .defaultValue("m1.large")
                            .label("인스턴스 사양")
                            .description("VM 의 CPU, 메모리, 디스크 조합. 설치본마다 이름 규칙이 다르다.")
                            .build(),
                    ProviderConfigKey.builder()
                            .key("anycloud-k8s:providerSpec.externalNetworkId")
                            .type("string")
                            .required(true)
                            .label("외부 네트워크")
                            .description("라우터를 붙일 외부망. 노드가 인터넷으로 나가는 길이다.")
                            .build(),
                    ProviderConfigKey.builder()
                            .key("anycloud-k8s:providerSpec.floatingIpPool")
                            .type("string")
                            .required(true)
                            .label("Floating IP 풀")
                            .description("노드에 붙일 공인 주소를 받아올 풀. 보통 외부망과 같은 이름이다.")
                            .build());
            case AWS -> List.of(
                    osImage("anycloud-k8s:awsImageName", "AWS AMI 이름 또는 ID (예: ubuntu-jammy-22.04-amd64)."));
            case ALIBABA -> List.of(
                    ProviderConfigKey.builder()
                            .key("anycloud-k8s:providerSpec.zone")
                            .type("string")
                            .required(true)
                            .label("존")
                            .description("리전 안에서 실제로 VM 이 올라갈 구역. 리전만으로는 정해지지 않는다.")
                            .build(),
                    ProviderConfigKey.builder()
                            .key("anycloud-k8s:osImage")
                            .type("string")
                            .required(true)
                            .label("OS 이미지")
                            .description("이름에 빌드 날짜가 붙어 주기적으로 갈린다. 목록에서 고른다.")
                            .build());
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
            case ALIBABA -> "ecs.g9i.large";
            case OPENSTACK -> "m1.large";
            case OCI -> "VM.Standard.E4.Flex";
            case AWS -> "t3.large";
            case PROXMOX -> "2-4096";
            case IBM -> "bx2-2x8";
        };
    }
}
