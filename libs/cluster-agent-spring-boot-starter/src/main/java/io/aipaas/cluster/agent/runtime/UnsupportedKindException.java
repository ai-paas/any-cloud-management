package io.aipaas.cluster.agent.runtime;

import java.util.List;

/**
 * Agent 의 {@code RESOLVE_RESOURCE} 가 입력 kind 를 RESTMapper 로 resolve 못한 케이스.
 * Backend 가 caller (UI / API consumer) 에 404 + suggestions 로 응답할 수 있도록 별도 exception.
 *
 * <p>suggestions 는 agent 측에서 Levenshtein 거리 ≤ 3 의 후보를 top-3 까지 반환한 결과.
 * 빈 list 면 fuzzy match 결과 없음 (또는 agent 가 discovery 호출 실패).
 */
public class UnsupportedKindException extends KubeRoutingException {

	private final String input;
	private final List<String> suggestions;

	public UnsupportedKindException(String input, String agentMessage, List<String> suggestions) {
		super("Agent could not resolve kind '" + input + "': " + agentMessage);
		this.input = input;
		this.suggestions = suggestions == null ? List.of() : List.copyOf(suggestions);
	}

	public String input() {
		return input;
	}

	public List<String> suggestions() {
		return suggestions;
	}
}
