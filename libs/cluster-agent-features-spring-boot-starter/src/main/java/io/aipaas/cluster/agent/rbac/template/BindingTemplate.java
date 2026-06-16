package io.aipaas.cluster.agent.rbac.template;

import java.util.List;

/**
 * 내부 canonical binding template.
 *
 * <p>{@link TieredBindingTemplate} 의 expand 결과 또는 운영자가 직접 작성한 advanced form
 * (forClusters: matchLabels) 둘 다 본 type 으로 정규화됨. starter SPI 와 default impl 은 모두 본
 * record 만 다룬다 — tier 의 존재를 모름.
 *
 * @param addonId addon 설치 hook 으로 자동 생성된 template 인 경우 addon id. null = 운영자가
 *               직접 작성한 catalog template. addon uninstall 시 label
 *               {@code aipaas.io/addon=<addonId>} 매칭으로 일괄 cleanup 가능.
 */
public record BindingTemplate(
		String id,
		LabelSelector forClusters,
		OidcGroupSelector oidcGroupSelector,
		List<TargetSubject> targetSubjects,
		List<RoleRef> roleRefs,
		String addonId) {

	public BindingTemplate {
		if (forClusters == null) forClusters = new LabelSelector(null);
		if (targetSubjects == null) targetSubjects = List.of();
		if (roleRefs == null) roleRefs = List.of();
	}

	/** 운영자 작성 catalog template (addon hook 외) 용 5-arg constructor. */
	public BindingTemplate(String id, LabelSelector forClusters,
			OidcGroupSelector oidcGroupSelector, List<TargetSubject> targetSubjects,
			List<RoleRef> roleRefs) {
		this(id, forClusters, oidcGroupSelector, targetSubjects, roleRefs, null);
	}
}
