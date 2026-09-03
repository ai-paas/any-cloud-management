package io.aipaas.cluster.agent.autoconfigure;

import jakarta.validation.constraints.Min;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Cluster Agent starter 설정 — prefix {@code cluster-agent}.
 *
 * <pre>{@code
 * cluster-agent:
 *   health:
 *     heartbeat-staleness-threshold: 90s
 *   exec:
 *     bind-timeout: 30s
 *     websocket-buffer-bytes: 65536
 *     websocket-path-pattern: /v1/clusters/*&#47;pods/*&#47;*&#47;exec
 *   routing:
 *     enabled: true                  # Day-2 K8s ops 를 agent 로 routing
 *     command-timeout-seconds: 15    # 일반 명령 timeout (apply 는 starter 측 60s+10s 고정)
 * }</pre>
 */
@ConfigurationProperties(prefix = "cluster-agent")
public record ClusterAgentProperties(Health health, Exec exec, Routing routing) {

	public ClusterAgentProperties {
		if (health == null) {
			health = new Health(null);
		}
		if (exec == null) {
			exec = new Exec(null, 0, null);
		}
		if (routing == null) {
			routing = new Routing(true, 0);
		}
	}

	/** Nested record 들도 자체 compact constructor 로 field-level default 처리 — host yaml 에
	 * 일부 field 만 명시되어 binding 후 null/0 으로 들어오는 케이스 안전 회피. */
	public record Health(Duration heartbeatStalenessThreshold) {
		public Health {
			if (heartbeatStalenessThreshold == null) {
				heartbeatStalenessThreshold = Duration.ofSeconds(90);
			}
		}
	}

	public record Exec(
			Duration bindTimeout,
			@Min(8192) int websocketBufferBytes,
			String websocketPathPattern) {
		public Exec {
			if (bindTimeout == null) bindTimeout = Duration.ofSeconds(30);
			if (websocketBufferBytes <= 0) websocketBufferBytes = 64 * 1024;
			if (websocketPathPattern == null || websocketPathPattern.isBlank()) {
				websocketPathPattern = "/v1/clusters/*/pods/*/*/exec";
			}
		}
	}

	public record Routing(
			boolean enabled,
			@Min(1) int commandTimeoutSeconds) {
		public Routing {
			if (commandTimeoutSeconds <= 0) commandTimeoutSeconds = 15;
		}
	}
}
