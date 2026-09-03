package com.aipaas.anycloud.domain.cluster.kubeconfig.internal;

import com.aipaas.anycloud.domain.cluster.kubeconfig.KubeconfigExportService;
import com.google.protobuf.Struct;
import com.google.protobuf.Value;
import io.aipaas.cluster.agent.runtime.AgentSessionRegistry;
import io.aipaas.cluster.agent.v1.CommandRequest;
import io.aipaas.cluster.agent.v1.CommandResponse;
import io.aipaas.cluster.agent.v1.CommandType;
import io.aipaas.cluster.agent.v1.ControlMessage;
import io.aipaas.cluster.agent.v1.Status;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class KubeconfigExportServiceImpl implements KubeconfigExportService {

    private static final Duration TIMEOUT = Duration.ofSeconds(15);

    private final AgentSessionRegistry sessionRegistry;

    @Override
    public IssuedKubeconfig issue(String clusterName, IssueRequest request) {
        Struct params = Struct.newBuilder()
                .putFields("namespace", strVal(request.namespace()))
                .putFields("service_account", strVal(request.serviceAccount()))
                .putFields(
                        "ttl_seconds",
                        strVal(String.valueOf(request.ttlSeconds() == null ? 3600 : request.ttlSeconds())))
                .putFields(
                        "cluster_name",
                        strVal(request.clusterDisplayName() == null ? clusterName : request.clusterDisplayName()))
                .putFields(
                        "context_namespace",
                        strVal(request.contextNamespace() == null ? request.namespace() : request.contextNamespace()))
                .build();

        ControlMessage.Builder builder = ControlMessage.newBuilder()
                .setCommand(CommandRequest.newBuilder()
                        .setType(CommandType.GENERATE_KUBECONFIG)
                        .setParams(params)
                        .setTimeoutSeconds((int) TIMEOUT.getSeconds())
                        .build());

        try {
            CommandResponse resp = sessionRegistry
                    .sendCommand(clusterName, builder, (int) TIMEOUT.getSeconds())
                    .get(TIMEOUT.toMillis() + 1000, TimeUnit.MILLISECONDS);
            if (resp.getStatus() != Status.OK) {
                throw new KubeconfigExportException(
                        resp.getErrorCode().isEmpty() ? "AGENT_ERROR" : resp.getErrorCode(), resp.getErrorMessage());
            }
            Map<String, Value> fields = resp.getResult().getFieldsMap();
            return new IssuedKubeconfig(
                    clusterName,
                    readString(fields, "namespace"),
                    readString(fields, "service_account"),
                    readString(fields, "expires_at"),
                    readString(fields, "kubeconfig_yaml"));
        } catch (AgentSessionRegistry.NoActiveSessionException e) {
            throw new KubeconfigExportException(
                    "NO_ACTIVE_AGENT", "no active agent stream for cluster " + clusterName, e);
        } catch (TimeoutException e) {
            throw new KubeconfigExportException("TIMEOUT", "timeout (cluster=" + clusterName + ")", e);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof AgentSessionRegistry.NoActiveSessionException
                    || cause instanceof AgentSessionRegistry.SessionClosedException) {
                throw new KubeconfigExportException("NO_ACTIVE_AGENT", "agent session unavailable", cause);
            }
            throw new KubeconfigExportException(
                    "AGENT_CALL_FAILED", cause == null ? e.toString() : cause.toString(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new KubeconfigExportException("INTERRUPTED", "interrupted", e);
        }
    }

    private static Value strVal(String s) {
        return Value.newBuilder().setStringValue(s == null ? "" : s).build();
    }

    private static String readString(Map<String, Value> fields, String key) {
        Value v = fields.get(key);
        return v == null ? null : v.getStringValue();
    }
}
