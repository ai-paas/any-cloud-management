package io.aipaas.cluster.provisioning.program.provisioner;

import com.pulumi.test.Mocks;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * 공유 Pulumi {@link Mocks} 구현 — 모든 CSP provisioner smoke test 가 사용.
 *
 * <p>책임:
 *
 * <ul>
 *   <li>resource 생성 mock — 인풋 state 그대로 + fake id 반환. 표준 attribute (publicIp/privateIp/ipv4Address
 *       /address/email/keyName/networkIp 등) 를 type 별로 가짜 값으로 채워, 후속 instance 가 의존하는
 *       Output 이 실제 값을 갖도록 한다.
 *   <li>function call mock — getAvailabilityZones/getZones/getAmi/getImage/getImages/getAvailabilityDomains
 *       등 7 provider 의 lookup 함수 모두 fake 응답.
 * </ul>
 *
 * <p>실제 CSP API 호출 없이 provisioner 의 wiring (자원 의존 그래프, output 키 노출) 만 검증.
 */
public final class SmokeMocks implements Mocks {

    private static final List<String> FAKE_AZ_NAMES = List.of("zone-a", "zone-b", "zone-c");

    private static final java.util.concurrent.atomic.AtomicInteger NUMERIC_ID_SEQ =
            new java.util.concurrent.atomic.AtomicInteger(1000);

    @Override
    public CompletableFuture<ResourceResult> newResourceAsync(ResourceArgs args) {
        Map<String, Object> state = new HashMap<>();
        if (args.inputs != null) {
            state.putAll(args.inputs);
        }
        // type 별 표준 output attribute 채우기 — provisioner 가 .id() / .address() / .publicIp() 등으로 참조.
        fillStandardOutputs(args.type, args.name, state);
        // DigitalOcean Droplet 은 numeric ID 사용 (DO API 의 ID 가 int) — Firewall.dropletIds 가 Integer 요구.
        String id;
        if (args.type.endsWith(":Droplet") || args.type.endsWith(":SshKey")) {
            id = String.valueOf(NUMERIC_ID_SEQ.getAndIncrement());
        } else {
            id = args.name + "-mockid";
        }
        return CompletableFuture.completedFuture(new ResourceResult(Optional.of(id), state));
    }

    @Override
    public CompletableFuture<Map<String, Object>> callAsync(CallArgs args) {
        return CompletableFuture.completedFuture(fakeCall(args.token));
    }

    private static void fillStandardOutputs(String type, String name, Map<String, Object> state) {
        // 모든 resource 가 가짜 id 보유.
        state.putIfAbsent("id", name + "-mockid");

        // EC2 / GCE / Azure VM / OCI / Alibaba ECS 인스턴스 — public/private IP 채우기.
        if (type.contains(":Instance") || type.contains("VirtualMachine") || type.contains("Droplet")) {
            state.putIfAbsent("publicIp", "203.0.113.10");
            state.putIfAbsent("privateIp", "10.0.0.10");
            state.putIfAbsent("ipv4Address", "203.0.113.10");
            state.putIfAbsent("ipv4AddressPrivate", "10.0.0.10");
            state.putIfAbsent("accessIpV4", "10.0.0.10");
            // Azure NIC.ipConfigurations[0] 응답 mocking — privateIPAddress 필드.
        }
        // GCP Address / Azure PublicIPAddress 의 address 필드.
        if (type.endsWith(":Address") || type.contains("PublicIPAddress")) {
            state.putIfAbsent("address", "203.0.113.10");
            state.putIfAbsent("ipAddress", "203.0.113.10");
        }
        // OpenStack FloatingIp.
        if (type.endsWith(":FloatingIp")) {
            state.putIfAbsent("address", "203.0.113.10");
        }
        // GCP ServiceAccount.
        if (type.endsWith(":Account") || type.endsWith(":ServiceAccount")) {
            state.putIfAbsent("email", name + "@example.iam.gserviceaccount.com");
        }
        // EC2 KeyPair / Alibaba KeyPair / OpenStack Keypair / GCP / Azure SshKey.
        if (type.contains(":KeyPair") || type.contains(":Keypair") || type.contains(":SshKey")) {
            state.putIfAbsent("keyName", name);
            state.putIfAbsent("name", name);
            state.putIfAbsent("publicKey", "ssh-rsa AAAA...");
        }
        // TLS PrivateKey.
        if (type.endsWith(":PrivateKey")) {
            state.putIfAbsent("privateKeyPem", "-----BEGIN RSA PRIVATE KEY-----\nMOCK\n-----END RSA PRIVATE KEY-----");
            state.putIfAbsent("publicKeyOpenssh", "ssh-rsa AAAAMOCK");
        }
        // IAM Role / RAM Role / OCI DynamicGroup — name 필드 채우기.
        if (type.contains(":Role") || type.contains(":DynamicGroup")) {
            state.putIfAbsent("name", name);
        }
        // VPC / Network / Subnet / Subnetwork / VSwitch 등 — name 보존.
        if (type.contains(":Vpc")
                || type.contains(":Network")
                || type.contains(":Subnet")
                || type.contains(":Switch")
                || type.contains(":Vcn")) {
            state.putIfAbsent("name", name);
        }
        // ResourceGroup.
        if (type.contains(":ResourceGroup")) {
            state.putIfAbsent("name", name);
        }
        // Azure NIC ipConfigurations response — privateIPAddress 가 Optional 안에 있어야 함.
        if (type.contains("network:NetworkInterface") && !type.contains("Association")) {
            // ipConfigurations 가 List<NetworkInterfaceIPConfigurationResponse> — 빈 리스트로 두면 Output 의
            // Optional.empty() 가 되어 provisioner 의 .applyValue 가 안전하게 처리.
        }
        // OCI VCN defaultRouteTableId / defaultSecurityListId — provisioner 가 직접 참조.
        if (type.endsWith(":Vcn")) {
            state.putIfAbsent("defaultRouteTableId", name + "-rt-mockid");
            state.putIfAbsent("defaultSecurityListId", name + "-sl-mockid");
        }
    }

    private static Map<String, Object> fakeCall(String token) {
        if (token == null) return Map.of();
        // AWS getAvailabilityZones / getAmi
        if (token.endsWith("getAvailabilityZones") || token.endsWith("getAvailabilityZones:getAvailabilityZones")) {
            return Map.of("names", FAKE_AZ_NAMES, "zoneIds", FAKE_AZ_NAMES);
        }
        if (token.endsWith("getAmi") || token.endsWith("getAmi:getAmi")) {
            return Map.of("id", "ami-mock1234567890", "imageId", "ami-mock1234567890");
        }
        // GCP getZones / getImage
        if (token.endsWith("getZones") || token.endsWith("getZones:getZones")) {
            return Map.of("names", FAKE_AZ_NAMES);
        }
        if (token.endsWith("getImage:getImage")) {
            return Map.of(
                    "selfLink", "projects/ubuntu-os-cloud/global/images/family/ubuntu-2404-lts-amd64",
                    "id", "ubuntu-2404-lts-amd64");
        }
        // OCI getAvailabilityDomains
        if (token.endsWith("getAvailabilityDomains:getAvailabilityDomains")) {
            return Map.of("availabilityDomains", List.of(Map.of("name", "Uocm:US-ASHBURN-AD-1")));
        }
        // Alibaba getZones / getImages
        if (token.endsWith("getZones:getZones")) {
            return Map.of("zones", List.of(Map.of("id", "cn-hangzhou-a")));
        }
        if (token.endsWith("getImages:getImages")) {
            return Map.of(
                    "images",
                    List.of(Map.of("id", "ubuntu_24_04_x64_20G_alibase_mock", "name", "ubuntu_24_04_x64_mock")));
        }
        return Map.of();
    }
}
