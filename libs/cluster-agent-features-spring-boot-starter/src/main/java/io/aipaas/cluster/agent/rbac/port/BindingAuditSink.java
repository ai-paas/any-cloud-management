package io.aipaas.cluster.agent.rbac.port;

import io.aipaas.cluster.agent.rbac.audit.BindingAuditEvent;

/**
 * Binding apply/delete 의 audit log 출력 SPI.
 *
 * <p>default 구현 ({@code LoggingBindingAuditSink}) 은 SLF4J INFO + MDC. 호스트가 DB 영속화 또는
 * 외부 SIEM (Splunk/Datadog/CloudWatch) 필요하면 본 SPI 만 override.
 *
 * <p>동기 호출 — audit 누락이 binding apply 실패보다 위험. 호스트 impl 이 비동기 원하면 자체 큐
 * 사용 (단 audit 손실 시 fail-closed 보장 필요).
 */
public interface BindingAuditSink {

	void record(BindingAuditEvent event);
}
