package io.aipaas.cluster.provisioning.api;

import java.util.List;
import java.util.Map;

/**
 * {@code pulumi preview --json} 의 구조화 결과.
 *
 * @param stackName          preview 대상 stack.
 * @param stackExistedBefore preview 전에 stack 이 이미 존재했는지 — false 면 preview 용으로
 *                           임시 생성 후 정리된 것 (신규 cluster 의 create 미리보기).
 * @param changeSummary      op → count (create / update / delete / same / replace ...).
 * @param steps              계획된 resource 단위 변경 목록.
 */
public record ProvisioningPreview(
		String stackName,
		boolean stackExistedBefore,
		Map<String, Integer> changeSummary,
		List<PlannedStep> steps) {

	/**
	 * 계획된 단일 resource 변경.
	 *
	 * @param op   Pulumi op (create / update / delete / same / replace ...).
	 * @param type resource type (예: aws:ec2/instance:Instance).
	 * @param name resource 논리 이름 (URN 마지막 segment).
	 */
	public record PlannedStep(String op, String type, String name) {
	}

	/** same 외의 op 가 하나라도 있으면 true — drift / 변경 예정 판단용. */
	public boolean hasChanges() {
		return changeSummary != null && changeSummary.entrySet().stream()
				.anyMatch(e -> !"same".equals(e.getKey()) && e.getValue() != null && e.getValue() > 0);
	}
}
