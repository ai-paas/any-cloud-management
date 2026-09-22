package com.aipaas.anycloud.domain.provisioning.capability;

import com.aipaas.anycloud.domain.cluster.model.GpuInstanceClassifier;
import com.aipaas.anycloud.domain.provisioning.VmClusterEntity;
import com.aipaas.anycloud.domain.provisioning.VmClusterRepository;
import com.aipaas.anycloud.domain.provisioning.bootstrap.support.VmClusterBootstrapSnapshotService;
import com.aipaas.anycloud.domain.provisioning.model.VmClusterInternalRequestSnapshot;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Component
@RequiredArgsConstructor
public class VmClusterGpuSpecImpl implements VmClusterGpuSpec {

    private final VmClusterRepository vmClusterRepository;
    private final VmClusterBootstrapSnapshotService snapshotService;

    @Override
    @Transactional(readOnly = true)
    public boolean requestedGpuNodes(String clusterName) {
        try {
            Optional<VmClusterEntity> found = vmClusterRepository.findById(clusterName);
            if (found.isEmpty()) {
                return false;
            }
            VmClusterInternalRequestSnapshot spec =
                    snapshotService.read(found.get().getRequestConfig());
            if (spec == null) {
                return false;
            }
            return GpuInstanceClassifier.isGpu(spec.getClusterProvider(), spec.getMasterVmSpec())
                    || GpuInstanceClassifier.isGpu(spec.getClusterProvider(), spec.getWorkerVmSpec());
        } catch (Exception e) {
            log.warn("GPU 요청 여부 확인 실패 cluster={}: {}", clusterName, e.toString());
            return false;
        }
    }
}
