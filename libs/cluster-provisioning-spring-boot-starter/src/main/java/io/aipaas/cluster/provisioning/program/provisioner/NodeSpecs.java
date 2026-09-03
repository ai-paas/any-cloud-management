package io.aipaas.cluster.provisioning.program.provisioner;

import com.pulumi.core.Output;
import io.aipaas.cluster.provisioning.program.ClusterSpec;
import io.aipaas.cluster.provisioning.program.KubeadmUserData;
import java.util.ArrayList;
import java.util.List;

/**
 * Defaults 적용된 ClusterSpec 으로부터 master/worker NodeSpec list 를 생성.
 * Go {@code infra/pulumi/pkg/provisioner/interface.go::NodeSpecsFor} 등가물.
 */
public final class NodeSpecs {

    private NodeSpecs() {}

    public static List<NodeSpec> from(ClusterSpec spec) {
        Output<String> masterUserData = Output.of(KubeadmUserData.master(spec));
        Output<String> workerUserData = Output.of(KubeadmUserData.worker(spec));
        int maxSubnets = spec.subnetCidrs() == null || spec.subnetCidrs().isEmpty() ? 1 : spec.subnetCidrs().size();

        List<NodeSpec> nodes = new ArrayList<>(spec.masterCount() + spec.workerCount());
        for (int i = 0; i < spec.masterCount(); i++) {
            nodes.add(new NodeSpec(
                    InstanceRole.MASTER,
                    i,
                    spec.masterInstanceType(),
                    spec.osImage() == null ? "" : spec.osImage(),
                    false, // master 는 항상 on-demand
                    masterUserData,
                    i % maxSubnets,
                    spec.rootDiskSizeGb()));
        }
        for (int i = 0; i < spec.workerCount(); i++) {
            nodes.add(new NodeSpec(
                    InstanceRole.WORKER,
                    i,
                    spec.workerInstanceType(),
                    spec.osImage() == null ? "" : spec.osImage(),
                    spec.useSpot(),
                    workerUserData,
                    i % maxSubnets,
                    spec.rootDiskSizeGb()));
        }
        return nodes;
    }
}
