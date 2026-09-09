package io.aipaas.cluster.agent.observability.stack;

import io.aipaas.cluster.agent.core.AgentLifecycleListener;
import io.aipaas.cluster.agent.observability.port.ClusterCapabilitiesSink;
import io.aipaas.cluster.agent.v1.Heartbeat;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/** Heartbeat 의 {@code AgentHealth.gpu_node_count} 를 보고 {@link ClusterCapabilitiesSink} 에 반영하는 lifecycle listener. */
@Slf4j
@RequiredArgsConstructor
public class GpuCapabilityHeartbeatListener implements AgentLifecycleListener {

    private final ClusterCapabilitiesSink sink;

    /** clusterName → 마지막으로 sink 에 보고한 값 (boolean). 변경 감지용. */
    private final ConcurrentMap<String, Boolean> lastReported = new ConcurrentHashMap<>();

    @Override
    public void onHeartbeat(String clusterName, Heartbeat heartbeat) {
        if (heartbeat == null || !heartbeat.hasHealth()) {
            return;
        }
        // proto default 0 — agent 가 측정 못 했거나 GPU 없음 양쪽 모두 0. 안전한 boolean 매핑은 > 0.
        boolean hasGpu = heartbeat.getHealth().getGpuNodeCount() > 0;

        Boolean prev = lastReported.get(clusterName);
        if (prev != null && prev.booleanValue() == hasGpu) {
            // 동일 값 — sink 호출 skip (DB write 회피).
            return;
        }

        try {
            sink.setHasGpuNodes(clusterName, hasGpu);
            lastReported.put(clusterName, hasGpu);
            log.info("GPU capability updated cluster={} has_gpu_nodes={}", clusterName, hasGpu);
        } catch (Exception e) {
            // sink 실패는 swallow — 다음 heartbeat 때 재시도됨 (prev 갱신 X).
            log.warn("GPU capability sink failed cluster={} target={}: {}", clusterName, hasGpu, e.toString());
        }
    }

    @Override
    public void onStreamDisconnected(String clusterName, String agentInstanceId) {
        // Cluster 가 다시 연결될 때 첫 heartbeat 는 강제로 반영하도록 cache 제거.
        lastReported.remove(clusterName);
    }
}
