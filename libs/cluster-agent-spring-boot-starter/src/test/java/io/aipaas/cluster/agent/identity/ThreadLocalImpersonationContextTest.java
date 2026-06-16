package io.aipaas.cluster.agent.identity;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * {@link ThreadLocalImpersonationContext} 의 set/clear/withIdentity 회귀.
 *
 * <p>ThreadLocal leak 방지가 핵심 — 각 테스트 끝나면 명시적으로 clear() 호출 (afterEach).
 */
class ThreadLocalImpersonationContextTest {

	private final ThreadLocalImpersonationContext ctx = new ThreadLocalImpersonationContext();

	@AfterEach
	void cleanup() {
		ThreadLocalImpersonationContext.clear();
	}

	@Test
	void currentIsEmpty_whenNothingSet() {
		assertThat(ctx.current()).isEmpty();
	}

	@Test
	void setThenCurrent_returnsSameIdentity() {
		ImpersonationIdentity alice = ImpersonationIdentity.of("alice", List.of("dev"));
		ThreadLocalImpersonationContext.set(alice);

		Optional<ImpersonationIdentity> result = ctx.current();

		assertThat(result).isPresent();
		assertThat(result.get().user()).isEqualTo("alice");
		assertThat(result.get().groups()).containsExactly("dev");
	}

	@Test
	void clear_removesIdentityFromHolder() {
		ThreadLocalImpersonationContext.set(ImpersonationIdentity.of("alice"));
		ThreadLocalImpersonationContext.clear();

		assertThat(ctx.current()).isEmpty();
	}

	@Test
	void withIdentity_runsActionWithIdentity_restoresAfter() {
		// 진입 전: 비어 있음.
		assertThat(ctx.current()).isEmpty();

		String observed = ThreadLocalImpersonationContext.withIdentity(
				ImpersonationIdentity.of("bob", List.of("admins")),
				() -> ctx.current().map(ImpersonationIdentity::user).orElse("none"));

		assertThat(observed).isEqualTo("bob");
		// 종료 후: previous 가 null 이었으므로 다시 비어 있음 (clear 처리).
		assertThat(ctx.current()).isEmpty();
	}

	@Test
	void withIdentity_actionThrows_stillClearsHolder() {
		assertThat(ctx.current()).isEmpty();

		try {
			ThreadLocalImpersonationContext.withIdentity(
					ImpersonationIdentity.of("bob"),
					() -> { throw new IllegalStateException("boom"); });
		} catch (IllegalStateException ignored) {
		}

		// finally 가 holder 정리.
		assertThat(ctx.current()).isEmpty();
	}

	@Test
	void withIdentity_nestedScope_restoresOuterIdentity() {
		ImpersonationIdentity outer = ImpersonationIdentity.of("outer");
		ThreadLocalImpersonationContext.set(outer);

		String observedInner = ThreadLocalImpersonationContext.withIdentity(
				ImpersonationIdentity.of("inner"),
				() -> ctx.current().map(ImpersonationIdentity::user).orElse("none"));

		assertThat(observedInner).isEqualTo("inner");
		// nested 종료 후 outer 복원.
		assertThat(ctx.current().map(ImpersonationIdentity::user)).hasValue("outer");
	}

	@Test
	void identityIsThreadLocal_notVisibleInOtherThread() throws Exception {
		ThreadLocalImpersonationContext.set(ImpersonationIdentity.of("main-user"));

		String observedFromAnother = java.util.concurrent.CompletableFuture.supplyAsync(
				() -> ctx.current().map(ImpersonationIdentity::user).orElse("none")).get();

		assertThat(observedFromAnother).isEqualTo("none");
		assertThat(ctx.current().map(ImpersonationIdentity::user)).hasValue("main-user");
	}

	@Test
	void emptyContextSentinel_alwaysReturnsEmpty() {
		ImpersonationContext empty = ImpersonationContext.empty();
		assertThat(empty.current()).isEmpty();
	}

	@Test
	void identityValidation_blankUserRejected() {
		org.junit.jupiter.api.Assertions.assertThrows(
				IllegalArgumentException.class,
				() -> new ImpersonationIdentity("  ", List.of(), java.util.Map.of()));
	}

	@Test
	void identityValidation_nullUserRejected() {
		org.junit.jupiter.api.Assertions.assertThrows(
				NullPointerException.class,
				() -> new ImpersonationIdentity(null, List.of(), java.util.Map.of()));
	}

	@Test
	void identityIsImmutable_externalListMutationDoesNotLeak() {
		List<String> mutable = new java.util.ArrayList<>(List.of("dev"));
		ImpersonationIdentity id = new ImpersonationIdentity("alice", mutable, java.util.Map.of());

		mutable.add("admin");

		assertThat(id.groups()).containsExactly("dev");
	}
}
