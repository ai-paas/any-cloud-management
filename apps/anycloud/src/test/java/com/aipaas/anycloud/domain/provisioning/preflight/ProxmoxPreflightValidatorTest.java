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
 * 이름이 틀린 요청을 인프라 생성 전에 세운다.
 *
 * <p>노드, datastore, 브리지는 Pulumi 가 VM 을 만들기 시작한 뒤에야 확인된다. 그때 실패하면 이미
 * 만들어진 자원이 롤백 대상으로 남는다.
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
        // 문서 예시의 pve1 을 그대로 복사해 쓰는 경우가 많다. 실제 이름은 설치할 때 정한 호스트명이다.
        Map<String, String> config = config();
        config.put("anycloud-k8s:providerSpec.nodeName", "pve1");

        assertThatThrownBy(() -> validator.validate(credential(), config))
                .isInstanceOf(CustomException.class)
                .hasMessageContaining("proxmox");
    }

    @Test
    void aZfsDiskDatastoreIsAccepted() {
        // ZFS 로 설치하면 디스크 스토리지가 local-zfs 다. 기본값을 덮어 쓸 수 있어야 한다.
        assertThatCode(() -> validator.validate(credential(), config("datastoreId", "local-zfs")))
                .doesNotThrowAnyException();
    }

    @Test
    void anImageDatastoreWithoutImportContentIsRejected() {
        // local 에 import 를 켜지 않으면 이미지 다운로드가 거부된다. 기본 설치는 꺼져 있다.
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
        // 여기까지 왔다면 정적 검증이 통과시킨 것이다. 같은 것을 두 번 막지 않는다.
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
        // 401 은 토큰 문제라 진단이 명확하다. Pulumi 까지 끌고 가면 같은 실패를 늦게 본다.
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
         * 모자라면 남은 노드가 DHCP 로 떨어지는데, cloud 이미지에 qemu-guest-agent 가 없어 받은
         * 주소를 알 수 없다. BOOTSTRAP 에서야 드러나고 그때는 VM 이 이미 과금된다.
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
        // 순서로 짝을 짓기 때문에 개수가 어긋나면 노드가 남의 포트로 붙는다.
        assertThatThrownBy(() -> validator.validate(credential(), staticConfig("192.168.0.200,192.168.0.201", "2200")))
                .isInstanceOf(CustomException.class)
                .hasMessageContaining("순서");
    }

    @Test
    void dhcpRequestsSkipTheAddressChecks() {
        assertThatCode(() -> validator.validate(credential(), config())).doesNotThrowAnyException();
    }
}
