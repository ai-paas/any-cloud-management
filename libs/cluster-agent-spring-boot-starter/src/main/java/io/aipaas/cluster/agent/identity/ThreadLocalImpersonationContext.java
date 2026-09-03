package io.aipaas.cluster.agent.identity;

import java.util.Optional;
import java.util.function.Supplier;

/**
 * Servlet thread-per-request 모델 기반 default {@link ImpersonationContext}.
 *
 * <p>WebMVC interceptor (backend 측) 가 request 진입 시 {@link #set} 호출, afterCompletion 에서
 * {@link #clear} 호출 → 같은 thread 의 후속 starter API 호출이 자동으로 holder 의 identity 사용.
 *
 * <p>Async / Reactor / virtual thread 환경에선 propagation 안 됨 — backend 가 명시적 propagation
 * (ContextSnapshot / TransmittableThreadLocal 등) 필요. 본 default 는 thread-local 단순성 우선.
 *
 * <p>thread-safety: ThreadLocal 자체. set/clear 는 반드시 try-finally 또는 interceptor 의
 * pre/afterCompletion 쌍으로 균형 보장 — leak 시 다음 request 가 잘못된 identity 로 호출.
 */
public class ThreadLocalImpersonationContext implements ImpersonationContext {

	private static final ThreadLocal<ImpersonationIdentity> HOLDER = new ThreadLocal<>();

	@Override
	public Optional<ImpersonationIdentity> current() {
		return Optional.ofNullable(HOLDER.get());
	}

	/** Request 진입 시 호출 — interceptor 의 preHandle 에서. */
	public static void set(ImpersonationIdentity identity) {
		HOLDER.set(identity);
	}

	/** Request 종료 시 호출 — interceptor 의 afterCompletion 에서. ThreadLocal leak 방지 필수. */
	public static void clear() {
		HOLDER.remove();
	}

	/**
	 * 일시적 identity 컨텍스트 — finally 보장. async 경로에서 명시적으로 identity 를 끼울 때.
	 * <pre>{@code
	 * ThreadLocalImpersonationContext.withIdentity(alice, () -> {
	 *     kubeResourceService.listResourcesPaginated(...);
	 *     return null;
	 * });
	 * }</pre>
	 */
	public static <T> T withIdentity(ImpersonationIdentity identity, Supplier<T> action) {
		ImpersonationIdentity previous = HOLDER.get();
		HOLDER.set(identity);
		try {
			return action.get();
		} finally {
			if (previous == null) {
				HOLDER.remove();
			} else {
				HOLDER.set(previous);
			}
		}
	}
}
