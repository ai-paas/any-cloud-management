package com.aipaas.anycloud.domain.provisioning.remote.internal;

import com.aipaas.anycloud.common.util.CommandExecutionSupport;
import com.aipaas.anycloud.domain.provisioning.VmClusterEntity;
import com.aipaas.anycloud.domain.provisioning.bootstrap.support.VmClusterNodeResolver;
import com.aipaas.anycloud.domain.provisioning.properties.PulumiProperties;
import com.aipaas.anycloud.domain.provisioning.remote.SshJump;
import com.aipaas.anycloud.domain.provisioning.remote.SshJumpEnvironment;
import com.aipaas.anycloud.domain.provisioning.remote.SshJumpOptions;
import com.aipaas.anycloud.domain.provisioning.remote.VmClusterRemoteAccessService;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class VmClusterRemoteAccessServiceImpl implements VmClusterRemoteAccessService {

    private final PulumiProperties pulumiProperties;
    private final ClusterSshJumpResolver sshJumpResolver;
    private final VmClusterNodeResolver nodeResolver;

    @Override
    public String runOnHost(
            VmClusterEntity vmCluster,
            Map<String, Object> outputs,
            String host,
            int port,
            String command,
            Duration timeout) {
        try {
            return executeSsh(vmCluster, outputs, host, port, command, timeout);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to execute remote command on host " + host + ":" + port, e);
        }
    }

    @Override
    public String runOnHost(
            VmClusterEntity vmCluster, Map<String, Object> outputs, String host, String command, Duration timeout) {
        return runOnHost(vmCluster, outputs, host, nodeResolver.sshPortOf(outputs, host), command, timeout);
    }

    @Override
    public String runOnMaster(
            VmClusterEntity vmCluster, Map<String, Object> outputs, String command, Duration timeout) {
        VmClusterNodeResolver.VmClusterNode master = nodeResolver.masterNode(outputs);
        String host = master == null || master.host() == null || master.host().isBlank()
                ? resolveMasterHost(outputs)
                : master.host();
        int port = master == null ? VmClusterNodeResolver.DEFAULT_SSH_PORT : master.port();
        return runOnHost(vmCluster, outputs, host, port, command, timeout);
    }

    @Override
    public String readSudoFileOnMaster(
            VmClusterEntity vmCluster, Map<String, Object> outputs, String remotePath, Duration timeout) {
        return runOnMaster(vmCluster, outputs, "sudo cat " + shellQuote(remotePath), timeout);
    }

    private String executeSsh(
            VmClusterEntity vmCluster,
            Map<String, Object> outputs,
            String host,
            int port,
            String command,
            Duration timeout)
            throws IOException {
        String privateKeyPem = stringValue(outputs.get("sshPrivateKeyPem"));
        if (privateKeyPem == null || privateKeyPem.isBlank()) {
            throw new IllegalStateException("Missing sshPrivateKeyPem in Pulumi outputs");
        }
        if (host == null || host.isBlank()) {
            throw new IllegalStateException("Missing SSH host in Pulumi outputs");
        }

        Path runtimeDir = pulumiProperties.resolveRuntimeDir();
        Files.createDirectories(runtimeDir);
        Path privateKeyPath = runtimeDir.resolve(vmCluster.getClusterName() + "-ssh-id_rsa");

        try {
            Files.writeString(privateKeyPath, privateKeyPem, StandardCharsets.UTF_8);
            privateKeyPath.toFile().setReadable(false, false);
            privateKeyPath.toFile().setReadable(true, true);
            privateKeyPath.toFile().setWritable(true, true);

            List<String> sshCommand = new ArrayList<>();
            sshCommand.add("ssh");
            sshCommand.add("-o");
            sshCommand.add("StrictHostKeyChecking=no");
            sshCommand.add("-o");
            sshCommand.add("UserKnownHostsFile=/dev/null");
            // 노드가 사설망이면 직접 닿지 못한다. 켜져 있을 때만 붙는다.
            SshJump jump = sshJumpResolver.resolve(vmCluster);
            sshCommand.addAll(SshJumpOptions.args(jump));
            sshCommand.add("-i");
            sshCommand.add(privateKeyPath.toString());
            if (port != VmClusterNodeResolver.DEFAULT_SSH_PORT) {
                sshCommand.add("-p");
                sshCommand.add(String.valueOf(port));
            }
            sshCommand.add(pulumiProperties.getSshUser() + "@" + host);
            sshCommand.add("bash -lc " + shellQuote(command));

            CommandExecutionSupport.CommandExecutionResult result;
            try (SshJumpEnvironment jumpEnv = SshJumpEnvironment.prepare(jump, pulumiProperties.resolveRuntimeDir())) {
                result = CommandExecutionSupport.execute(sshCommand, null, jumpEnv.env(), timeout);
            }
            if (!result.isSuccess()) {
                throw new IllegalStateException("Remote command failed on host " + host + " stderr=" + result.stderr()
                        + " stdout=" + result.stdout());
            }
            return result.stdout();
        } finally {
            Files.deleteIfExists(privateKeyPath);
        }
    }

    private String resolveMasterHost(Map<String, Object> outputs) {
        return firstNonBlank(
                stringValue(outputs.get("masterPublicDns")),
                stringValue(outputs.get("masterPublicIp")),
                stringValue(outputs.get("masterPrivateIp")));
    }

    private String shellQuote(String value) {
        return "'" + value.replace("'", "'\"'\"'") + "'";
    }

    private String stringValue(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }
}
