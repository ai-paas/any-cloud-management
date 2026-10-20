package com.aipaas.anycloud.domain.provisioning.preflight;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.aipaas.anycloud.common.error.exception.CustomException;
import com.aipaas.anycloud.domain.provisioning.preflight.validation.ProxmoxPreflightValidator;
import com.aipaas.anycloud.domain.provisioning.proxmox.ProxmoxApiClient;
import com.aipaas.anycloud.testsupport.AbstractUnitTest;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * 이름이 잘못된 요청을 인프라 생성 전에 차단.
 *
 * <p>노드, datastore, 브리지는 Pulumi 의 VM 생성 시작 이후에 확인. 그 시점 실패는 생성된 자원을
 * 롤백 대상으로 남김.
 */
class ProxmoxPreflightValidatorTest extends AbstractUnitTest {

    private static final String NODES = """
            {"data":[{"node":"proxmox","status":"online"}]}""";
    private static final String STORAGE =
            """
            {"data":[{"storage":"local","content":"backup,iso,import,vztmpl"},
                     {"storage":"local-lvm","content":"rootdir,images"},
                     {"storage":"local-zfs","content":"images,rootdir"}]}""";
    private static final String NETWORK =
            """
            {"data":[{"type":"bridge","iface":"vmbr0"},{"type":"eth","iface":"enp4s0"}]}""";

    private final ProxmoxPreflightValidator validator =
            new ProxmoxPreflightValidator(new ProxmoxApiClient(new ObjectMapper()));
    private HttpServer server;
    private int status = 200;

    @BeforeEach
    void startStubPve() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api2/json/", exchange -> {
            String path = exchange.getRequestURI().getPath();
            String body = path.endsWith("/storage") ? STORAGE : path.endsWith("/network") ? NETWORK : NODES;
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, status == 200 ? bytes.length : -1);
            if (status == 200) exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
    }

    @AfterEach
    void stopStubPve() {
        server.stop(0);
    }

    private Map<String, String> credential() {
        Map<String, String> env = new HashMap<>();
        env.put("PROXMOX_VE_ENDPOINT", "http://127.0.0.1:" + server.getAddress().getPort());
        env.put("PROXMOX_VE_API_TOKEN_ID", "aipaas@pve!provisioner");
        env.put("PROXMOX_VE_API_TOKEN_SECRET", "00000000-0000-0000-0000-000000000000");
        return env;
    }

    private Map<String, String> config(String... overrides) {
        Map<String, String> config = new HashMap<>();
        config.put("anycloud-k8s:providerSpec.nodeName", "proxmox");
        for (int i = 0; i < overrides.length; i += 2) {
            config.put("anycloud-k8s:providerSpec." + overrides[i], overrides[i + 1]);
        }
        return config;
    }

    @Test
    void aRequestMatchingTheHostPasses() {
        assertThatCode(() -> validator.validate(credential(), config())).doesNotThrowAnyException();
    }

    @Test
    void anUnknownNodeIsRejectedWithTheAvailableOnes() {
        // 문서 예시의 pve1 을 그대로 복사해 사용하는 경우가 많음. 실제 이름은 설치 시 지정한 호스트명.
        Map<String, String> config = config();
        config.put("anycloud-k8s:providerSpec.nodeName", "pve1");

        assertThatThrownBy(() -> validator.validate(credential(), config))
                .isInstanceOf(CustomException.class)
                .hasMessageContaining("proxmox");
    }

    @Test
    void aZfsDiskDatastoreIsAccepted() {
        // ZFS 설치 시 디스크 스토리지는 local-zfs. 기본값 재정의 가능해야 함.
        assertThatCode(() -> validator.validate(credential(), config("datastoreId", "local-zfs")))
                .doesNotThrowAnyException();
    }

    @Test
    void anImageDatastoreWithoutImportContentIsRejected() {
        // local 에 import 미활성 시 이미지 다운로드 거부. 기본 설치는 비활성 상태.
        assertThatThrownBy(() -> validator.validate(credential(), config("imageDatastoreId", "local-lvm")))
                .isInstanceOf(CustomException.class)
                .hasMessageContaining("import");
    }

    @Test
    void aDiskDatastoreWithoutImagesContentIsRejected() {
        assertThatThrownBy(() -> validator.validate(credential(), config("datastoreId", "local")))
                .isInstanceOf(CustomException.class)
                .hasMessageContaining("images");
    }

    @Test
    void anUnknownDatastoreIsRejectedWithTheCandidates() {
        assertThatThrownBy(() -> validator.validate(credential(), config("datastoreId", "ceph-pool")))
                .isInstanceOf(CustomException.class)
                .hasMessageContaining("local-lvm");
    }

    @Test
    void anUnknownBridgeIsRejected() {
        assertThatThrownBy(() -> validator.validate(credential(), config("networkBridge", "vmbr9")))
                .isInstanceOf(CustomException.class)
                .hasMessageContaining("vmbr0");
    }

    @Test
    void anIncompleteCredentialFallsThroughToTheStaticRules() {
        // 이 지점은 정적 검증 통과 후. 동일 항목 중복 차단 불필요.
        assertThatCode(() -> validator.validate(Map.of(), config())).doesNotThrowAnyException();
    }

    @Test
    void anUnreachableHostDefersToPulumi() {
        // CSP API 장애로 프로비저닝을 막으면 복구 수단이 없다. 다른 CSP 검증과 같은 처리다.
        Map<String, String> env = credential();
        env.put("PROXMOX_VE_ENDPOINT", "http://127.0.0.1:1");

        assertThatCode(() -> validator.validate(env, config())).doesNotThrowAnyException();
    }

    @Test
    void anAuthenticationFailureIsReportedInsteadOfDeferred() {
        // 401 은 토큰 문제로 진단이 명확. Pulumi 단계까지 진행하면 동일 실패를 늦게 검출.
        status = 401;

        assertThatThrownBy(() -> validator.validate(credential(), config()))
                .isInstanceOf(CustomException.class)
                .hasMessageContaining("인증");
    }

    private Map<String, String> staticConfig(String ips, String ports) {
        Map<String, String> config = config();
        config.put("anycloud-k8s:masterCount", "1");
        config.put("anycloud-k8s:workerCount", "1");
        config.put("anycloud-k8s:providerSpec.nodeIps", ips);
        config.put("anycloud-k8s:providerSpec.gateway", "192.168.0.1");
        if (ports != null) config.put("anycloud-k8s:providerSpec.sshPorts", ports);
        return config;
    }

    @Test
    void aMatchingSetOfStaticAddressesPasses() {
        assertThatCode(() -> validator.validate(credential(), staticConfig("192.168.0.200,192.168.0.201", "2200,2201")))
                .doesNotThrowAnyException();
    }

    @Test
    void tooFewAddressesAreRejectedBeforeAnythingIsCreated() {
        /*
         * 부족하면 잔여 노드가 DHCP 로 전환되는데, cloud 이미지에 qemu-guest-agent 가 없어
         * 할당 주소 확인 불가. BOOTSTRAP 단계에서 드러나며 그 시점에 VM 은 이미 과금 중.
         */
        assertThatThrownBy(() -> validator.validate(credential(), staticConfig("192.168.0.200", null)))
                .isInstanceOf(CustomException.class)
                .hasMessageContaining("2");
    }

    @Test
    void aMissingGatewayIsRejected() {
        Map<String, String> config = staticConfig("192.168.0.200,192.168.0.201", null);
        config.remove("anycloud-k8s:providerSpec.gateway");

        assertThatThrownBy(() -> validator.validate(credential(), config))
                .isInstanceOf(CustomException.class)
                .hasMessageContaining("게이트웨이");
    }

    @Test
    void portCountMustMatchAddressCount() {
        // 순서로 짝을 맺으므로 개수 불일치 시 노드가 다른 노드의 포트로 접속.
        assertThatThrownBy(() -> validator.validate(credential(), staticConfig("192.168.0.200,192.168.0.201", "2200")))
                .isInstanceOf(CustomException.class)
                .hasMessageContaining("순서");
    }

    @Test
    void dhcpRequestsSkipTheAddressChecks() {
        assertThatCode(() -> validator.validate(credential(), config())).doesNotThrowAnyException();
    }
}
