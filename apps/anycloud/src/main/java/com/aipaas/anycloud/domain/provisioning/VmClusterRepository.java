package com.aipaas.anycloud.domain.provisioning;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.stereotype.Repository;

@Repository
public interface VmClusterRepository
        extends JpaRepository<VmClusterEntity, String>, JpaSpecificationExecutor<VmClusterEntity> {

    /** 조정 루프 대상 — READY / DEGRADED 클러스터. */
    List<VmClusterEntity> findByProvisioningStatusIn(
            java.util.Collection<com.aipaas.anycloud.domain.provisioning.model.VmClusterStatus> statuses);


    Optional<VmClusterEntity> findFirstByClusterNameOrderByCreatedAtDesc(String clusterName);

    List<VmClusterEntity> findAllByOrderByCreatedAtDesc();

    boolean existsByActiveRequestKey(String activeRequestKey);

    long countByCredentialIdAndActiveRequestKeyIsNotNull(String credentialId);
}
