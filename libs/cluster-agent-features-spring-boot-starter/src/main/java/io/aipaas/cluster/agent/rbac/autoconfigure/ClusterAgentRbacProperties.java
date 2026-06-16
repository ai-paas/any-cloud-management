package io.aipaas.cluster.agent.rbac.autoconfigure;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * RBAC starter 설정.
 */
@ConfigurationProperties(prefix = "cluster-rbac")
public record ClusterAgentRbacProperties(
		Templates templates,
		FleetView fleetView,
		Labels labels) {

	public ClusterAgentRbacProperties {
		if (templates == null) templates = new Templates(null);
		if (fleetView == null) fleetView = new FleetView(null);
		if (labels == null) labels = new Labels(null, null);
	}

	/** Template catalog source. */
	public record Templates(String classpathResource) {
		public Templates {
			if (classpathResource == null || classpathResource.isBlank()) {
				classpathResource = "binding-templates.yaml";
			}
		}
	}

	/** Fleet view 캐시 설정. */
	public record FleetView(Duration cacheTtl) {
		public FleetView {
			if (cacheTtl == null) cacheTtl = Duration.ofSeconds(30);
		}
	}

	/** label key 컨벤션 — 운영 환경마다 namespace 가 다를 수 있어 override 가능. */
	public record Labels(String managedBy, String prefix) {
		public Labels {
			if (managedBy == null || managedBy.isBlank()) managedBy = "anycloud";
			if (prefix == null || prefix.isBlank()) prefix = "aipaas.io";
		}

		public String managedByKey() {
			return prefix + "/managed-by";
		}

		public String templateKey() {
			return prefix + "/template";
		}

		public String oidcGroupKey() {
			return prefix + "/oidc-group";
		}

		public String addonKey() {
			return prefix + "/addon";
		}
	}
}
