package io.aipaas.cluster.agent.rbac.audit;

import java.time.Instant;
import java.util.Map;

/**
 * Binding apply/delete 의 audit event.
 *
 * <p>actor 컨벤션:
 * <ul>
 *   <li>운영자 UI 액션: OIDC {@code sub} 또는 username</li>
 *   <li>addon hook 자동 apply: {@code "system:addon:<name>"}</li>
 *   <li>break-glass 토큰 사용: {@code "break-glass:<source-ip>"} (Slack alert 권장)</li>
 * </ul>
 */
public record BindingAuditEvent(
		Instant occurredAt,
		Action action,
		String actor,
		String clusterId,
		String templateId,
		String k8sBindingName,
		String oidcGroup,
		String reason,
		Map<String, String> metadata) {

	public BindingAuditEvent {
		if (occurredAt == null) {
			throw new IllegalArgumentException("occurredAt 필수");
		}
		if (action == null) {
			throw new IllegalArgumentException("action 필수");
		}
		if (metadata == null) metadata = Map.of();
	}

	public enum Action {
		ATTEMPT,
		APPLY,
		UPDATE,
		DELETE,
		REJECTED
	}

	/** apply 시작 시점. */
	public static BindingAuditEvent attempt(String actor, String clusterId, String templateId,
			String oidcGroup, String reason) {
		return new BindingAuditEvent(Instant.now(), Action.ATTEMPT, actor, clusterId, templateId,
				null, oidcGroup, reason, Map.of());
	}

	/** apply 성공. */
	public static BindingAuditEvent applied(String actor, String clusterId, String templateId,
			String oidcGroup, String k8sBindingName) {
		return new BindingAuditEvent(Instant.now(), Action.APPLY, actor, clusterId, templateId,
				k8sBindingName, oidcGroup, null, Map.of());
	}

	/** apply 실패. */
	public static BindingAuditEvent rejected(String actor, String clusterId, String templateId,
			String oidcGroup, String reason) {
		return new BindingAuditEvent(Instant.now(), Action.REJECTED, actor, clusterId, templateId,
				null, oidcGroup, reason, Map.of());
	}

	/** delete. */
	public static BindingAuditEvent deleted(String actor, String clusterId, String templateId,
			String k8sBindingName, String reason) {
		return new BindingAuditEvent(Instant.now(), Action.DELETE, actor, clusterId, templateId,
				k8sBindingName, null, reason, Map.of());
	}
}
