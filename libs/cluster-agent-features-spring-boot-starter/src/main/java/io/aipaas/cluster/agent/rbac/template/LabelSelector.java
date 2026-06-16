package io.aipaas.cluster.agent.rbac.template;

import java.util.Map;
import java.util.Objects;

/**
 * Cluster label selector. K8s LabelSelector 호환 (matchLabels only).
 *
 * <p>{@code null} 또는 빈 {@code matchLabels} 는 "모든 cluster 매칭" 의미.
 */
public record LabelSelector(Map<String, String> matchLabels) {

	public LabelSelector {
		if (matchLabels == null) matchLabels = Map.of();
	}

	/** 주어진 cluster labels 이 본 selector 와 매칭하는지 검사. */
	public boolean matches(Map<String, String> clusterLabels) {
		if (matchLabels.isEmpty()) return true;
		if (clusterLabels == null) return false;
		return matchLabels.entrySet().stream()
				.allMatch(e -> Objects.equals(clusterLabels.get(e.getKey()), e.getValue()));
	}
}
