package io.aipaas.cluster.agent.observability.alerts;

/**
 * 단일 PrometheusRule rule-set 의 카탈로그 entry.
 *
 * <p>본 record 는 YAML 자원 한 파일을 표현한다. {@link AlertRuleCatalog} 가 classpath 의
 * {@code alert-rules/*.yaml} 을 로드해 id 별로 보관. id 는 파일명 (확장자 제외).
 *
 * <p>manifestYaml 은 그대로 cluster-agent 의 APPLY_MANIFEST 에 전달되는 PrometheusRule CR.
 * {@code ${NAMESPACE}} / {@code ${RELEASE}} placeholder 가 install 시 치환된다.
 *
 * @param id            "node" / "pod" / "control-plane" 등 안정 식별자
 * @param displayName   UI 표시용 한국어 라벨
 * @param description   본 rule set 이 다루는 영역 요약
 * @param ruleCount     포함된 PrometheusRule alert 개수 (UI 미리보기용)
 * @param manifestYaml  PrometheusRule CR YAML (placeholder 포함)
 */
public record AlertRuleSet(
		String id,
		String displayName,
		String description,
		int ruleCount,
		String manifestYaml) {
}
