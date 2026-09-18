package com.aipaas.anycloud.domain.provisioning.remote.internal;

import com.aipaas.anycloud.domain.provisioning.VmClusterEntity;
import com.aipaas.anycloud.domain.provisioning.VmClusterRepository;
import com.aipaas.anycloud.domain.provisioning.properties.PulumiProperties;
import com.aipaas.anycloud.domain.provisioning.query.VmClusterNodeRows;
import com.aipaas.anycloud.domain.provisioning.remote.NodeSshCommand;
import com.aipaas.anycloud.domain.provisioning.remote.SshJump;
import com.aipaas.anycloud.domain.provisioning.remote.SshJumpEnvironment;
import com.aipaas.anycloud.domain.provisioning.remote.VmClusterNodeSshSessionService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.socket.BinaryMessage;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketSession;

/**
 * 노드에 붙는 대화형 ssh 세션.
 *
 * <p>파드 exec 는 에이전트를 거치지만 노드는 클러스터 밖이라 백엔드가 직접 {@code ssh} 를 띄운다.
 * 클러스터가 죽어 파드 셸을 못 쓸 때 쓰라고 만든 것이라 에이전트에 기대면 안 된다.
 *
 * <p>개인키는 Pulumi outputs 에만 있다. 프로세스가 끝나면 지운다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class VmClusterNodeSshSessionServiceImpl implements VmClusterNodeSshSessionService {

    private final VmClusterRepository vmClusterRepository;
    private final PulumiProperties pulumiProperties;
    private final ClusterSshJumpResolver sshJumpResolver;
    private final ClusterSshUserResolver sshUserResolver;
    private final ObjectMapper objectMapper;
    private final com.aipaas.anycloud.domain.credential.CspCredentialService cspCredentialService;
    private final io.aipaas.cluster.provisioning.api.ProvisioningService provisioningService;

    private final Map<String, Session> sessions = new ConcurrentHashMap<>();

    private record Session(Process process, Path keyPath, Thread pump, SshJumpEnvironment jumpEnv) {}

    @Override
    public void open(WebSocketSession socket, String vmName, String host) {
        VmClusterEntity cluster = vmClusterRepository
                .findFirstByClusterNameOrderByCreatedAtDesc(vmName)
                .orElseThrow(() -> new IllegalStateException("클러스터를 찾을 수 없습니다: " + vmName));

        // 요청받은 host 가 이 클러스터의 노드인지 확인한다. 확인하지 않으면 임의의 주소로
        // 백엔드가 ssh 를 걸어주는 통로가 된다.
        List<VmClusterNodeRows.Row> nodes = VmClusterNodeRows.of(objectMapper, cluster);
        VmClusterNodeRows.Row matchedRow = nodes.stream()
                .filter(n -> Stream.of(n.publicIp(), n.privateIp(), n.publicDns())
                        .filter(Objects::nonNull)
                        .anyMatch(host::equals))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("이 클러스터의 노드가 아닙니다: " + host));
        // 노드 목록에 있다고 ssh 인자로 안전하지는 않다. 옵션처럼 생긴 값은 여기서 막는다.
        String target = NodeSshCommand.requireSafeHost(host);

        String privateKeyPem = privateKeyOf(cluster);
        Path keyPath = writeKey(privateKeyPem);

        try {
            // 노드가 사설망이면 점프 없이는 닿지 않는다. 프로비저닝과 같은 길을 쓴다.
            SshJump jump = sshJumpResolver.resolve(cluster);
            ProcessBuilder builder = new ProcessBuilder(NodeSshCommand.build(
                    keyPath.toString(), sshUserResolver.resolve(cluster), target, jump, matchedRow.sshPort()));
            builder.redirectErrorStream(true);
            // 비밀번호 bastion 은 프롬프트를 띄우는데 여기엔 터미널이 없다. askpass 로 넘긴다.
            // ssh 는 비밀번호가 필요해질 때 스크립트를 읽는다. 시작 직후 지우면 인증할 것이 없어
            // 세션이 끝날 때까지 들고 있는다.
            SshJumpEnvironment jumpEnv = SshJumpEnvironment.prepare(jump, pulumiProperties.resolveRuntimeDir());
            builder.environment().putAll(jumpEnv.env());
            Process process = builder.start();

            Thread pump = Thread.ofVirtual().start(() -> pumpToSocket(process, socket));
            sessions.put(socket.getId(), new Session(process, keyPath, pump, jumpEnv));
        } catch (IOException e) {
            deleteQuietly(keyPath);
            throw new IllegalStateException("ssh 를 시작하지 못했습니다: " + e.getMessage(), e);
        }
    }

    @Override
    public void write(WebSocketSession socket, byte[] data) {
        Session session = sessions.get(socket.getId());
        if (session == null) {
            return;
        }
        try {
            OutputStream stdin = session.process().getOutputStream();
            stdin.write(data);
            stdin.flush();
        } catch (IOException e) {
            close(socket);
        }
    }

    /**
     * 창 크기는 ssh 를 다시 띄우지 않고는 바꿀 수 없다 — 원격 TTY 크기는 접속할 때 정해진다.
     * 프레임을 버리되 세션은 유지한다. 끊으면 창을 줄였다는 이유로 작업이 날아간다.
     */
    @Override
    public void control(WebSocketSession socket, String payload) {
        try {
            JsonNode node = objectMapper.readTree(payload);
            if (!"resize".equals(node.path("type").asText())) {
                log.debug("알 수 없는 제어 프레임: {}", payload);
            }
        } catch (Exception e) {
            log.debug("제어 프레임을 읽지 못했습니다: {}", e.toString());
        }
    }

    @Override
    public void close(WebSocketSession socket) {
        Session session = sessions.remove(socket.getId());
        if (session == null) {
            return;
        }
        session.process().destroy();
        deleteQuietly(session.keyPath());
        session.jumpEnv().close();
    }

    private void pumpToSocket(Process process, WebSocketSession socket) {
        byte[] buffer = new byte[4096];
        try (InputStream out = process.getInputStream()) {
            int read;
            while ((read = out.read(buffer)) != -1) {
                if (!socket.isOpen()) {
                    break;
                }
                // sendMessage 는 동시 호출을 허용하지 않는다. 여기 한 스레드만 보낸다.
                synchronized (socket) {
                    socket.sendMessage(new BinaryMessage(buffer, 0, read, true));
                }
            }
        } catch (Exception e) {
            log.debug("노드 SSH 출력 전달 종료: {}", e.toString());
        } finally {
            closeQuietly(socket);
        }
    }

    /**
     * DB 의 raw_outputs 에는 비밀이 REDACTED 로 들어간다 — 올바른 설계다. 개인키는 Pulumi 에서
     * 그때그때 복호화해 읽는다.
     */
    private String privateKeyOf(VmClusterEntity cluster) {
        if (cluster.getStackName() == null || cluster.getStackName().isBlank()) {
            throw new IllegalStateException("SSH 키가 아직 없습니다. 프로비저닝이 끝나야 접속할 수 있습니다.");
        }
        Map<String, String> credentialEnvironment =
                cspCredentialService.resolveEnvironment(cluster.getClusterProvider(), cluster.getCredentialId());
        Map<String, Object> outputs =
                provisioningService.stackOutputs(cluster.getStackName(), true, credentialEnvironment);
        Object pem = outputs == null ? null : outputs.get("sshPrivateKeyPem");
        if (pem == null || pem.toString().isBlank()) {
            throw new IllegalStateException("SSH 키를 찾지 못했습니다. 프로비저닝이 끝나야 접속할 수 있습니다.");
        }
        return pem.toString();
    }

    private Path writeKey(String pem) {
        try {
            Path dir = pulumiProperties.resolveRuntimeDir();
            Files.createDirectories(dir);
            Path keyPath = createKeyFile(dir);
            Files.writeString(keyPath, pem, StandardCharsets.UTF_8);
            keyPath.toFile().setReadable(false, false);
            keyPath.toFile().setReadable(true, true);
            return keyPath;
        } catch (IOException e) {
            throw new IllegalStateException("SSH 키를 저장하지 못했습니다", e);
        }
    }

    /**
     * 개인키를 담을 빈 파일을 런타임 디렉터리 안에 만든다.
     *
     * <p>이름에 클러스터 이름을 넣지 않는다. 그 값은 요청 경로에서 오므로 파일명에 섞으면
     * 디렉터리를 벗어날 여지가 생기고, 그대로 ssh 인자로도 흘러간다. 진단에 쓰려고 넣었던
     * 것이라 잃는 것이 없다.
     */
    static Path createKeyFile(Path dir) throws IOException {
        return Files.createTempFile(dir, "node-term-", ".pem");
    }

    private void deleteQuietly(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException e) {
            log.warn("임시 SSH 키를 지우지 못했습니다: {}", path);
        }
    }

    private void closeQuietly(WebSocketSession socket) {
        try {
            if (socket.isOpen()) {
                socket.close(CloseStatus.NORMAL);
            }
        } catch (Exception ignored) {
            // 이미 닫혔다.
        }
    }
}
