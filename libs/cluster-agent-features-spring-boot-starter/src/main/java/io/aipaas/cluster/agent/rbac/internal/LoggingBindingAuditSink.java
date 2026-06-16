package io.aipaas.cluster.agent.rbac.internal;

import io.aipaas.cluster.agent.rbac.audit.BindingAuditEvent;
import io.aipaas.cluster.agent.rbac.port.BindingAuditSink;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;

/**
 * SLF4J 기반 audit sink default.
 *
 * <p>MDC key 로 structured logging 호환 (logback 의 json encoder 가 자동 인식). 호스트가 DB 영속화
 * 또는 외부 SIEM 통합 원하면 본 SPI 만 override.
 */
@Slf4j
public class LoggingBindingAuditSink implements BindingAuditSink {

	@Override
	public void record(BindingAuditEvent event) {
		try {
			MDC.put("audit.action", event.action().name());
			MDC.put("audit.actor", nullSafe(event.actor()));
			MDC.put("audit.cluster", nullSafe(event.clusterId()));
			MDC.put("audit.template", nullSafe(event.templateId()));
			MDC.put("audit.k8sBinding", nullSafe(event.k8sBindingName()));
			MDC.put("audit.oidcGroup", nullSafe(event.oidcGroup()));

			switch (event.action()) {
				case APPLY, UPDATE ->
					log.info("RBAC binding {} cluster={} template={} group={} k8sName={} actor={}",
							event.action(), event.clusterId(), event.templateId(), event.oidcGroup(),
							event.k8sBindingName(), event.actor());
				case DELETE ->
					log.info("RBAC binding DELETE cluster={} template={} k8sName={} actor={} reason={}",
							event.clusterId(), event.templateId(), event.k8sBindingName(), event.actor(),
							event.reason());
				case REJECTED ->
					log.warn("RBAC binding REJECTED cluster={} template={} group={} actor={} reason={}",
							event.clusterId(), event.templateId(), event.oidcGroup(), event.actor(),
							event.reason());
				case ATTEMPT ->
					log.debug("RBAC binding ATTEMPT cluster={} template={} group={} actor={} reason={}",
							event.clusterId(), event.templateId(), event.oidcGroup(), event.actor(),
							event.reason());
			}
		} finally {
			MDC.remove("audit.action");
			MDC.remove("audit.actor");
			MDC.remove("audit.cluster");
			MDC.remove("audit.template");
			MDC.remove("audit.k8sBinding");
			MDC.remove("audit.oidcGroup");
		}
	}

	private static String nullSafe(String s) {
		return s == null ? "" : s;
	}
}
