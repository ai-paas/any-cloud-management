package com.aipaas.anycloud.domain.cluster.internal;

import com.aipaas.anycloud.domain.cluster.NodeDebugPodService;
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
public class NodeDebugPodServiceImpl implements NodeDebugPodService {

    private static final Duration TIMEOUT = Duration.ofSeconds(20);

    private final AgentSessionRegistry sessionRegistry;

    @Override
    public DebugPodResult create(String clusterName, CreateRequest request) {
        Struct params = Struct.newBuilder()
                .putFields("node_name", strVal(request.nodeName()))
                .putFields("namespace", strVal(request.namespace() == null ? "" : request.namespace()))
                .putFields("image", strVal(request.image() == null ? "" : request.image()))
                .putFields("pod_name", strVal(request.podName() == null ? "" : request.podName()))
                .putFields(
                        "ttl_seconds",
                        strVal(String.valueOf(request.ttlSeconds() == null ? 1800 : request.ttlSeconds())))
                .build();

        ControlMessage.Builder builder = ControlMessage.newBuilder()
                .setCommand(CommandRequest.newBuilder()
                        .setType(CommandType.CREATE_NODE_DEBUG_POD)
                        .setParams(params)
                        .setTimeoutSeconds((int) TIMEOUT.getSeconds())
                        .build());

        try {
            CommandResponse resp = sessionRegistry
                    .sendCommand(clusterName, builder, (int) TIMEOUT.getSeconds())
                    .get(TIMEOUT.toMillis() + 1000, TimeUnit.MILLISECONDS);
            if (resp.getStatus() != Status.OK) {
                throw new NodeDebugPodException(
                        resp.getErrorCode().isEmpty() ? "AGENT_ERROR" : resp.getErrorCode(), resp.getErrorMessage());
            }
            Map<String, Value> fields = resp.getResult().getFieldsMap();
            return new DebugPodResult(
                    clusterName,
                    readString(fields, "node_name"),
                    readString(fields, "namespace"),
                    readString(fields, "pod_name"),
                    readString(fields, "expires_at"));
        } catch (AgentSessionRegistry.NoActiveSessionException e) {
            throw new NodeDebugPodException("NO_ACTIVE_AGENT", "no active agent stream for cluster " + clusterName, e);
        } catch (TimeoutException e) {
            throw new NodeDebugPodException("TIMEOUT", "timeout (cluster=" + clusterName + ")", e);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof AgentSessionRegistry.NoActiveSessionException
                    || cause instanceof AgentSessionRegistry.SessionClosedException) {
                throw new NodeDebugPodException("NO_ACTIVE_AGENT", "agent session unavailable", cause);
            }
            throw new NodeDebugPodException("AGENT_CALL_FAILED", cause == null ? e.toString() : cause.toString(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new NodeDebugPodException("INTERRUPTED", "interrupted", e);
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
