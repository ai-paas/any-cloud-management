package io.aipaas.cluster.agent.rbac.internal;

import static org.assertj.core.api.Assertions.assertThat;

import io.aipaas.cluster.agent.rbac.audit.BindingAuditEvent;
import org.junit.jupiter.api.Test;

class LoggingBindingAuditSinkTest {

	@Test
	void allActions_invokeWithoutThrowing() {
		var sink = new LoggingBindingAuditSink();
		sink.record(BindingAuditEvent.attempt("alice", "c1", "tpl", "team-x", "test"));
		sink.record(BindingAuditEvent.applied("alice", "c1", "tpl", "team-x", "aipaas-tpl-team-x"));
		sink.record(BindingAuditEvent.rejected("alice", "c1", "tpl", "team-x", "boom"));
		sink.record(BindingAuditEvent.deleted("alice", "c1", "tpl", "aipaas-tpl-team-x", "cleanup"));

		// MDC 가 record 호출 후 cleanup 되는지
		assertThat(org.slf4j.MDC.getCopyOfContextMap()).isNullOrEmpty();
	}
}
