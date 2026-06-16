package io.aipaas.cluster.agent.rbac.template;

import java.util.List;
import java.util.Map;

/**
 * 운영자 작성용 tiered template (default form). cluster tier 라벨 (default {@code anycloud.io/tier})
 * 기준으로 다른 권한 매핑.
 *
 * <p>{@link #expand()} 가 tier 별 단일 {@link BindingTemplate} N개로 정규화 — starter 내부는 항상
 * 단일 form 만 다룸.
 */
public record TieredBindingTemplate(
		String id,
		OidcGroupSelector oidcGroupSelector,
		List<TargetSubject> targetSubjects,
		String tierLabel,
		Map<String, List<RoleRef>> tiers) {

	public static final String DEFAULT_TIER_LABEL = "anycloud.io/tier";

	public TieredBindingTemplate {
		if (oidcGroupSelector == null) {
			throw new IllegalArgumentException("oidcGroupSelector 필수");
		}
		if (tiers == null || tiers.isEmpty()) {
			throw new IllegalArgumentException("tiers 가 최소 1개 entry 필요");
		}
		if (tierLabel == null || tierLabel.isBlank()) tierLabel = DEFAULT_TIER_LABEL;
		if (targetSubjects == null) targetSubjects = List.of();
	}

	/** tier 별 단일 BindingTemplate 으로 expand. id 는 {@code <id>@<tier>} 형식. */
	public List<BindingTemplate> expand() {
		return tiers.entrySet().stream()
				.map(e -> new BindingTemplate(
						id + "@" + e.getKey(),
						new LabelSelector(Map.of(tierLabel, e.getKey())),
						oidcGroupSelector,
						targetSubjects,
						e.getValue()))
				.toList();
	}
}
