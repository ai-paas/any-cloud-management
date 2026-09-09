package com.aipaas.anycloud.domain.provisioning.convergence;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface VmClusterComponentRepository extends JpaRepository<VmClusterComponentEntity, String> {

    List<VmClusterComponentEntity> findByVmClusterId(String vmClusterId);

    Optional<VmClusterComponentEntity> findByVmClusterIdAndComponentType(String vmClusterId, ComponentType type);
}
