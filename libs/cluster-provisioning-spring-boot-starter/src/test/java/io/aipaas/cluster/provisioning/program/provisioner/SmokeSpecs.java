package io.aipaas.cluster.provisioning.program.provisioner;

import io.aipaas.cluster.provisioning.program.ClusterSpec;
import io.aipaas.cluster.provisioning.program.DatabaseSpec;
import java.util.List;

/**
 * 공유 ClusterSpec builder — smoke test 가 CSP 별로 살짝 다른 입력을 만들 때 사용.
 *
 * <p>모든 spec 은 {@link ClusterSpec#normalize()} 를 통과해 정규화된 record 를 반환한다.
 */
public final class SmokeSpecs {

    private SmokeSpecs() {}

    public static ClusterSpec base(String provider) {
        return new ClusterSpec(
                provider,
                "smoke-" + provider,
                "test",
                "test-region",
                /* gcpProject */ "gcp-smoke",
                /* azureResourceGroup */ "smoke-rg",
                /* ociCompartmentId */ "ocid1.compartment.oc1..smoke",
                /* vpcCidr */ "10.0.0.0/16",
                /* subnetCidrs */ List.of("10.0.1.0/24", "10.0.2.0/24"),
                /* sshUser */ "ubuntu",
                /* masterInstanceType */ "test.master",
                /* workerInstanceType */ "test.worker",
                /* masterCount */ 1,
                /* workerCount */ 2,
                /* kubernetesVersion */ "1.31",
                /* podCidr */ "192.168.0.0/16",
                /* serviceCidr */ "10.96.0.0/12",
                /* joinToken */ "abcdef.1234567890abcdef",
                /* enableIngress */ false,
                /* enableGpuOperator */ false,
                /* openstackImageName */ "ubuntu-24.04",
                /* openstackFlavorName */ "m1.large",
                /* openstackExternalNetworkId */ "ext-net-id",
                /* openstackFloatingIpPool */ "public",
                /* database */ DatabaseSpec.disabled(),
                /* useSpot */ false,
                /* osImage */ "",
                /* rootDiskSizeGb */ 50)
                .normalize();
    }
}
