package io.aipaas.cluster.provisioning.core;

/**
 * Cluster identity 의 host-agnostic 표현.
 *
 * <p>anycloud 의 {@code VmClusterEntity} 가 갖는 read-only field 중 starter 의 provisioning
 * service 가 필요로 하는 최소 subset 만 정의. host backend (anycloud / 다른 SaaS) 가 자신의 Entity 를 이
 * interface 로 wrapping → starter 가 host model 모름.
 *
 * <p>PulumiStateBackupScheduler / Validator 가 본 interface 의 stream 으로 작업.
 */
public interface ClusterDescriptor {

	/** 사용자 부여 cluster 이름 (Pulumi stack name 의 일부). */
	String getClusterName();

	/** provider 식별자 — aws, gcp, azure, openstack, proxmox 등 normalized. */
	String getProvider();

	/** Pulumi stack name (project_dir 기준 유일). */
	String getStackName();

	/** Backup 시 RustFS prefix 또는 cloud bucket prefix 의 일부. */
	String getBackupPrefix();

	/** kubeconfig (PEM-encoded). null 이면 미보유 (provisioning 단계 미완료). */
	String getKubeconfig();

	/** 현재 active 인가? (state machine 기준 ACTIVE 또는 DEGRADED 등). */
	boolean isActive();

	/** ID — host 측 PK. audit log 외에 사용 안 함. */
	String getId();
}
