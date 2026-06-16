package io.aipaas.cluster.provisioning.service;

import io.aipaas.cluster.provisioning.core.PulumiPreviewResult;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * {@code pulumi preview --json} stdout 파서.
 *
 * <p>preview 의 {@code --json} 은 (up 의 event-stream 과 달리) 단일 JSON document 를 출력:
 * <pre>{"steps": [{"op": "create", "urn": "...", "newState": {"type": "aws:..."}}, ...],
 *  "changeSummary": {"create": 12}}</pre>
 *
 * <p>스키마 변동에 관대하게 — 알 수 없는 field 는 무시, 필수 field 누락 step 은 건너뛴다.
 */
public final class PulumiPreviewParser {

	private static final ObjectMapper MAPPER = new ObjectMapper();

	private PulumiPreviewParser() {
	}

	/**
	 * preview stdout 을 구조화. 파싱 불가 시 빈 summary/steps 반환 — preview 자체는 성공했는데
	 * 응답만 비는 상황이 throw 보다 낫다 (caller 는 exitCode 로 성공 여부를 이미 안다).
	 */
	public static PulumiPreviewResult parse(String stackName, boolean stackExistedBefore, String stdout) {
		Map<String, Integer> changeSummary = new LinkedHashMap<>();
		List<PulumiPreviewResult.PlannedStep> steps = new ArrayList<>();
		if (stdout == null || stdout.isBlank()) {
			return new PulumiPreviewResult(stackName, stackExistedBefore, changeSummary, steps);
		}
		try {
			JsonNode root = MAPPER.readTree(stdout);
			JsonNode summary = root.path("changeSummary");
			summary.fields().forEachRemaining(e -> {
				if (e.getValue().isInt()) {
					changeSummary.put(e.getKey(), e.getValue().intValue());
				}
			});
			for (JsonNode step : root.path("steps")) {
				String op = step.path("op").asText(null);
				String urn = step.path("urn").asText(null);
				if (op == null || urn == null) {
					continue;
				}
				String type = step.path("newState").path("type").asText(null);
				if (type == null) {
					type = step.path("oldState").path("type").asText(null);
				}
				steps.add(new PulumiPreviewResult.PlannedStep(op, type, nameFromUrn(urn)));
			}
		} catch (Exception e) {
			// malformed JSON — 빈 결과 반환. caller 가 raw stdout 로그로 추적 가능.
		}
		return new PulumiPreviewResult(stackName, stackExistedBefore, changeSummary, steps);
	}

	/** URN 형식: urn:pulumi:stack::project::type::name — 마지막 :: segment 가 논리 이름. */
	private static String nameFromUrn(String urn) {
		int idx = urn.lastIndexOf("::");
		return idx < 0 ? urn : urn.substring(idx + 2);
	}
}
