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
 * <p>파드 exec 는 에이전트 경유, 노드는 클러스터 외부이므로 백엔드가 {@code ssh} 직접 실행.
 * 클러스터 장애로 파드 셸 사용이 불가할 때의 경로이므로 에이전트 의존 금지.
 *
 * <p>개인키는 Pulumi outputs 에만 존재. 프로세스 종료 시 삭제.
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

        // 요청 host 가 해당 클러스터의 노드인지 확인. 미확인 시 임의 주소로의 ssh 중계 경로가 됨.
        List<VmClusterNodeRows.Row> nodes = VmClusterNodeRows.of(objectMapper, cluster);
        VmClusterNodeRows.Row matchedRow = nodes.stream()
                .filter(n -> Stream.of(n.publicIp(), n.privateIp(), n.publicDns())
                        .filter(Objects::nonNull)
                        .anyMatch(host::equals))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("이 클러스터의 노드가 아닙니다: " + host));
        // 노드 목록 포함 여부와 ssh 인자 안전성은 별개. 옵션 형태 값은 여기서 차단.
        String target = NodeSshCommand.requireSafeHost(host);

        String privateKeyPem = privateKeyOf(cluster);
        Path keyPath = writeKey(privateKeyPem);

        try {
            // 사설망 노드는 점프 호스트 없이 접근 불가. 프로비저닝과 동일 경로 사용.
            SshJump jump = sshJumpResolver.resolve(cluster);
            ProcessBuilder builder = new ProcessBuilder(NodeSshCommand.build(
                    keyPath.toString(), sshUserResolver.resolve(cluster), target, jump, matchedRow.sshPort()));
            builder.redirectErrorStream(true);
            // 비밀번호 bastion 은 프롬프트를 요구하나 터미널 부재. askpass 로 전달.
            // ssh 는 비밀번호 필요 시점에 스크립트를 읽으므로 시작 직후 삭제하면 인증 불가.
            // 세션 종료까지 유지.
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
     * 창 크기는 ssh 재실행 없이 변경 불가. 원격 TTY 크기는 접속 시점에 확정. 프레임은 폐기하고
     * 세션은 유지. 연결 종료 시 창 크기 변경만으로 작업 유실.
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
     * DB 의 raw_outputs 에는 비밀값이 REDACTED 로 저장. 개인키는 Pulumi 에서 호출 시점에
     * 복호화해 조회.
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
     * 개인키 저장용 빈 파일을 런타임 디렉터리에 생성.
     *
     * <p>파일명에 클러스터 이름 미포함. 해당 값은 요청 경로 유래이므로 파일명에 사용 시 디렉터리
     * 이탈 가능성이 생기고 ssh 인자로도 전달.
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
