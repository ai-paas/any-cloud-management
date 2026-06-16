package com.aipaas.anycloud.service.provisioning;

import com.aipaas.anycloud.domain.provisioning.VmClusterEntity;
import com.aipaas.anycloud.domain.provisioning.VmClusterRepository;
import com.aipaas.anycloud.domain.provisioning.model.VmClusterStatus;
import io.aipaas.cluster.provisioning.core.ClusterDescriptor;
import io.aipaas.cluster.provisioning.core.ClusterDescriptorRepository;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * cluster-provisioning starter 의 {@link ClusterDescriptorRepository} 포트를 anycloud 의
 * {@link VmClusterRepository} 로 연결하는 어댑터.
 *
 * <p>starter 의 backup scheduler/validator 가 host model(VmClusterEntity)을 모른 채 cluster 목록을
 * 조회하도록, VmClusterEntity 를 {@link ClusterDescriptor} read-only view 로 wrapping 한다.
 */
@Component
@RequiredArgsConstructor
public class VmClusterDescriptorRepositoryAdapter implements ClusterDescriptorRepository {

    private final VmClusterRepository vmClusterRepository;

    @Override
    public List<ClusterDescriptor> findAll() {
        return vmClusterRepository.findAll().stream()
                .map(VmClusterDescriptorRepositoryAdapter::toDescriptor)
                .toList();
    }

    @Override
    public List<ClusterDescriptor> findAllActive() {
        return vmClusterRepository.findAll().stream()
                .filter(e -> e.getProvisioningStatus() == VmClusterStatus.READY)
                .map(VmClusterDescriptorRepositoryAdapter::toDescriptor)
                .toList();
    }

    @Override
    public Optional<ClusterDescriptor> findById(String id) {
        return vmClusterRepository.findById(id).map(VmClusterDescriptorRepositoryAdapter::toDescriptor);
    }

    @Override
    public Optional<ClusterDescriptor> findByClusterName(String clusterName) {
        return vmClusterRepository
                .findFirstByClusterNameOrderByCreatedAtDesc(clusterName)
                .map(VmClusterDescriptorRepositoryAdapter::toDescriptor);
    }

    private static ClusterDescriptor toDescriptor(VmClusterEntity e) {
        return new ClusterDescriptor() {
            @Override
            public String getClusterName() {
                return e.getClusterName();
            }

            @Override
            public String getProvider() {
                return e.getClusterProvider();
            }

            @Override
            public String getStackName() {
                return e.getStackName();
            }

            @Override
            public String getBackupPrefix() {
                return e.getStackName();
            }

            @Override
            public String getKubeconfig() {
                return null; // backup scheduler/validator 미사용 — VmClusterEntity 는 kubeconfig 미보유.
            }

            @Override
            public boolean isActive() {
                return e.getProvisioningStatus() == VmClusterStatus.READY;
            }

            @Override
            public String getId() {
                return e.getId();
            }
        };
    }
}
