package io.aipaas.cluster.agent.rbac.internal;

import com.fasterxml.jackson.databind.JsonNode;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import io.aipaas.cluster.agent.core.AgentLifecycleListener;
import io.aipaas.cluster.agent.rbac.autoconfigure.ClusterAgentRbacProperties;
import io.aipaas.cluster.agent.rbac.port.BindingFleetView;
import io.aipaas.cluster.agent.runtime.KubeResourcePage;
import io.aipaas.cluster.agent.runtime.KubeResourceService;
import io.aipaas.cluster.agent.v1.AgentEvent;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import lombok.extern.slf4j.Slf4j;

/**
 * Caffeine-cached fleet view. cluster 별 TTL 캐시, miss 시 agent gRPC fan-out.
 *
 * <p>cluster-agent 의 K8s informer push event ({@link AgentEvent} event_type
 * {@code rbac.binding.changed} / {@code rbac.binding.sync.start} / {@code rbac.binding.sync.end})
 * 수신 시 cache 즉시 invalidate. {@link AgentLifecycleListener} 구현으로 Layer 1 의 event hook
 * 활용.
 */
@Slf4j
public class SimpleBindingFleetView implements BindingFleetView, AgentLifecycleListener {

	private static final String EVENT_BINDING_CHANGED = "rbac.binding.changed";
	private static final String EVENT_BINDING_SYNC_START = "rbac.binding.sync.start";
	private static final String EVENT_BINDING_SYNC_END = "rbac.binding.sync.end";

	private final KubeResourceService kubeService;
	private final ClusterAgentRbacProperties.Labels labels;
	private final Cache<String, List<AppliedBinding>> perCluster;

	public SimpleBindingFleetView(KubeResourceService kubeService, ClusterAgentRbacProperties props) {
		this.kubeService = kubeService;
		this.labels = props.labels();
		Duration ttl = props.fleetView().cacheTtl();
		this.perCluster = Caffeine.newBuilder()
				.expireAfterWrite(ttl)
				.maximumSize(1000)
				.build();
	}

	/** informer push event 또는 운영자 명시 호출 — 단일 cluster cache 무효화. */
	public void invalidate(String clusterName) {
		perCluster.invalidate(clusterName);
	}

	/**
	 * Layer 1 의 AgentLifecycleListener hook. agent 의 informer push event 처리.
	 *
	 * <p>handled event types:
	 * <ul>
	 *   <li>{@code rbac.binding.changed}    — 단일 binding ADD/UPDATE/DELETE</li>
	 *   <li>{@code rbac.binding.sync.start} — agent reconnect 후 full resync 시작</li>
	 *   <li>{@code rbac.binding.sync.end}   — full resync 완료</li>
	 * </ul>
	 *
	 * <p>모든 event 가 caching invalidate 트리거 — 다음 list() 호출이 fresh fetch.
	 */
	@Override
	public void onAgentEvent(String clusterName, AgentEvent event) {
		String eventType = event == null ? "" : event.getEventType();
		if (EVENT_BINDING_CHANGED.equals(eventType)
				|| EVENT_BINDING_SYNC_START.equals(eventType)
				|| EVENT_BINDING_SYNC_END.equals(eventType)) {
			invalidate(clusterName);
			log.debug("Fleet view cache invalidated by agent event cluster={} type={}",
					clusterName, eventType);
		}
	}

	/** Agent disconnect 시 cache 의 그 cluster entry 도 stale 표시. */
	@Override
	public void onStreamDisconnected(String clusterName, String agentInstanceId) {
		invalidate(clusterName);
		log.debug("Fleet view cache invalidated by disconnect cluster={}", clusterName);
	}

	@Override
	public List<AppliedBinding> list(String clusterName) {
		return perCluster.get(clusterName, this::fetchFromCluster);
	}

	@Override
	public Map<String, List<AppliedBinding>> listAll(Collection<String> clusterNames) {
		Map<String, List<AppliedBinding>> out = new ConcurrentHashMap<>();
		for (String name : clusterNames) {
			out.put(name, list(name));
		}
		return out;
	}

	private List<AppliedBinding> fetchFromCluster(String clusterName) {
		String labelSelector = labels.managedByKey() + "=" + labels.managedBy();
		List<AppliedBinding> all = new ArrayList<>();
		all.addAll(fetchKind(clusterName, "ClusterRoleBinding", "", labelSelector));
		all.addAll(fetchKind(clusterName, "RoleBinding", "", labelSelector));
		return List.copyOf(all);
	}

	private List<AppliedBinding> fetchKind(String clusterName, String kind, String namespace,
			String labelSelector) {
		List<AppliedBinding> out = new ArrayList<>();
		String token = null;
		do {
			KubeResourcePage page = kubeService.listResourcesPaginated(clusterName, namespace, kind, 500,
					token, labelSelector);
			if (page == null || page.items() == null || !page.items().isArray()) break;
			for (JsonNode item : page.items()) {
				AppliedBinding b = toApplied(clusterName, item);
				if (b != null) out.add(b);
			}
			token = page.continueToken();
		} while (token != null && !token.isBlank());
		return out;
	}

	private AppliedBinding toApplied(String clusterName, JsonNode item) {
		String name = item.path("metadata").path("name").asText("");
		if (name.isEmpty()) return null;

		JsonNode labelNode = item.path("metadata").path("labels");
		Map<String, String> labelMap = new LinkedHashMap<>();
		if (labelNode.isObject()) {
			Iterator<Map.Entry<String, JsonNode>> it = labelNode.fields();
			while (it.hasNext()) {
				Map.Entry<String, JsonNode> e = it.next();
				labelMap.put(e.getKey(), e.getValue().asText(""));
			}
		}

		Instant appliedAt = parseCreationTimestamp(item.path("metadata").path("creationTimestamp").asText(""));
		return new AppliedBinding(
				clusterName,
				name,
				labelMap.getOrDefault(labels.templateKey(), ""),
				labelMap.getOrDefault(labels.oidcGroupKey(), ""),
				appliedAt,
				Map.copyOf(new HashMap<>(labelMap)));
	}

	private static Instant parseCreationTimestamp(String ts) {
		if (ts == null || ts.isBlank()) return null;
		try {
			return Instant.parse(ts);
		} catch (RuntimeException e) {
			return null;
		}
	}
}
