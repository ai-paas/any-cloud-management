package io.aipaas.cluster.agent.rbac.port;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * Fleet 전체의 적용된 binding 목록 SPI.
 *
 * <p>K8s 가 truth — 본 SPI 는 그 truth 의 read-side. default 구현 ({@code SimpleBindingFleetView})
 * 은 Caffeine cache + agent gRPC fan-out. informer push event 로 invalidate 추가 예정.
 */
public interface BindingFleetView {

	/** 단일 cluster 의 anycloud-managed binding 목록. */
	List<AppliedBinding> list(String clusterName);

	/** 여러 cluster 의 binding 일괄 조회. cluster 별 fan-out. */
	Map<String, List<AppliedBinding>> listAll(Collection<String> clusterNames);

	record AppliedBinding(
			String clusterName,
			String k8sBindingName,
			String templateId,
			String oidcGroup,
			Instant appliedAt,
			Map<String, String> labels) {}
}
