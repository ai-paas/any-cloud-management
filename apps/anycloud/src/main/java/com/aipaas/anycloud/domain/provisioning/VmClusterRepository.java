package com.aipaas.anycloud.domain.provisioning;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.stereotype.Repository;

@Repository
public interface VmClusterRepository
        extends JpaRepository<VmClusterEntity, String>, JpaSpecificationExecutor<VmClusterEntity> {

    Optional<VmClusterEntity> findFirstByClusterNameOrderByCreatedAtDesc(String clusterName);

    List<VmClusterEntity> findAllByOrderByCreatedAtDesc();

    boolean existsByActiveRequestKey(String activeRequestKey);

    long countByCredentialIdAndActiveRequestKeyIsNotNull(String credentialId);
}
