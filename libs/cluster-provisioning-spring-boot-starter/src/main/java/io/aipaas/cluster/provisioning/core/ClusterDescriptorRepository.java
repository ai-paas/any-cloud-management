package io.aipaas.cluster.provisioning.core;

import java.util.List;
import java.util.Optional;

/**
 * {@link ClusterDescriptor} 를 host 의 데이터 소스 (DB / cache) 에서 조회하는 port.
 *
 * <p>host 가 자신의 Repository (예: VmClusterRepository) 를 wrapping 해 본 interface 로 노출.
 * starter 의 provisioning service 가 cluster 식별만 알면 됨.
 *
 * <p>Minimum API — PulumiStateBackupScheduler 가 active cluster list 만 필요. 추가 read API 필요시 본
 * interface 확장 또는 별 port 분리.
 */
public interface ClusterDescriptorRepository {

	/** 모든 cluster — backup scheduler 의 sweep 대상 (active 외 상태도 stack 백업 대상). */
	List<ClusterDescriptor> findAll();

	/** 모든 active cluster. */
	List<ClusterDescriptor> findAllActive();

	/** ID 로 lookup. provisioning 진행 중 status 확인. */
	Optional<ClusterDescriptor> findById(String id);

	/** cluster name 으로 lookup — Pulumi stack 식별 시. */
	Optional<ClusterDescriptor> findByClusterName(String clusterName);
}
