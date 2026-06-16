package io.aipaas.cluster.agent.identity;

import java.util.Optional;

/**
 * Backend 의 현재 호출 컨텍스트에서 K8s Impersonation identity 를 조회하는 SPI.
 *
 * <p>Starter 는 본 interface 만 의존 — Spring Security / JWT / OIDC 등 인증 메커니즘에 직접
 * 결합되지 않는다. 모든 인증 backend 가 자체 구현 (또는 default {@link ThreadLocalImpersonationContext}
 * 를 그대로 사용 + interceptor 에서 set) 으로 주입.
 *
 * <p>호출 의미:
 * <ul>
 *   <li>{@link #current()} 가 빈 Optional → impersonation 미사용 (현재 admin-equivalent 동작).</li>
 *   <li>identity 보유 → starter 의 K8s 호출 path 가 자동으로 CommandRequest 의 impersonate_*
 *       필드에 채워 agent 로 전달.</li>
 * </ul>
 *
 * <p>Async / system 컨텍스트 (RabbitMQ listener, scheduled job) 에서는 holder 가 set 되지 않으므로
 * 자연스럽게 admin-equivalent. system action 임을 명시하려면 caller 가 {@link #empty()} 컨텍스트로
 * 진입.
 *
 * <p>Default 구현: {@link ThreadLocalImpersonationContext} — Servlet thread 마다 1개 slot. async
 * propagation 이 필요하면 backend 가 자체 구현으로 override.
 */
public interface ImpersonationContext {

	/**
	 * 현재 호출 thread 의 identity (있으면). 빈 Optional 이면 starter 가 impersonation field 를
	 * 채우지 않고 admin-equivalent 로 호출.
	 */
	Optional<ImpersonationIdentity> current();

	/** 명시적 "이 호출은 impersonation 미사용" sentinel — current() == empty 와 동일 의미. */
	static ImpersonationContext empty() {
		return Optional::empty;
	}
}
