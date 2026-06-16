package io.aipaas.cluster.agent.identity;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * K8s Impersonation 대상 user identity.
 *
 * <p>{@link ImpersonationContext} 가 backend 의 SecurityContext / gateway header 로부터 추출해
 * 보유한 채 starter 의 K8s 호출 path 에 자동 주입된다.
 *
 * <p>매핑:
 * <ul>
 *   <li>{@code user} → K8s rest.Config.Impersonate.UserName (헤더: {@code Impersonate-User})</li>
 *   <li>{@code groups} → K8s rest.Config.Impersonate.Groups (헤더: {@code Impersonate-Group})</li>
 *   <li>{@code extras} → K8s rest.Config.Impersonate.Extra (헤더: {@code Impersonate-Extra-<key>})</li>
 * </ul>
 *
 * <p>모두 비면 backend 의 admin-equivalent 동작 (wildcard RBAC) 그대로. 사용자 인증 toggle OFF 시
 * backend interceptor 가 holder 를 set 하지 않으므로 자연스럽게 빈 identity 상태.
 *
 * <p>Thread-safety: 불변 record. {@code List}/{@code Map} 은 생성 시점에 {@code copyOf} 로 사본 보관.
 */
public record ImpersonationIdentity(
		String user,
		List<String> groups,
		Map<String, List<String>> extras) {

	public ImpersonationIdentity {
		Objects.requireNonNull(user, "user is required");
		if (user.isBlank()) {
			throw new IllegalArgumentException("user must not be blank — use ImpersonationContext.empty() for system identity");
		}
		// defensive copy + immutability — caller 의 List 변형이 holder 안 entry 까지 새지 않도록.
		groups = groups == null ? List.of() : List.copyOf(groups);
		if (extras == null || extras.isEmpty()) {
			extras = Map.of();
		} else {
			// nested list 도 immutable copy.
			java.util.LinkedHashMap<String, List<String>> copy = new java.util.LinkedHashMap<>();
			for (Map.Entry<String, List<String>> e : extras.entrySet()) {
				copy.put(e.getKey(), e.getValue() == null ? List.of() : List.copyOf(e.getValue()));
			}
			extras = Collections.unmodifiableMap(copy);
		}
	}

	/** user 만 명시 (groups / extras 없음) 의 ergonomic ctor. */
	public static ImpersonationIdentity of(String user) {
		return new ImpersonationIdentity(user, List.of(), Map.of());
	}

	/** user + groups (extras 없음). */
	public static ImpersonationIdentity of(String user, List<String> groups) {
		return new ImpersonationIdentity(user, groups, Map.of());
	}
}
