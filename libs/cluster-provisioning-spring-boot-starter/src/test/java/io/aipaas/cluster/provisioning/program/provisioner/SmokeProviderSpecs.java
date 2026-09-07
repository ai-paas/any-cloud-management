package io.aipaas.cluster.provisioning.program.provisioner;

import io.aipaas.cluster.provisioning.program.ProviderName;
import io.aipaas.cluster.provisioning.program.ProviderSpec;

/** smoke spec 의 provider 전용 설정. provider 전용 값이 없는 CSP 는 null 이다. */
final class SmokeProviderSpecs {

    private SmokeProviderSpecs() {}

    static ProviderSpec of(String provider) {
        return switch (ProviderName.canonical(provider)) {
            case "gcp" -> new ProviderSpec.Gcp("gcp-smoke");
            case "azure" -> new ProviderSpec.Azure("smoke-rg");
            case "oci" -> new ProviderSpec.Oci("ocid1.compartment.oc1..smoke");
            case "openstack" -> new ProviderSpec.Openstack("ubuntu-24.04", "m1.large", "ext-net-smoke", "public");
            default -> null;
        };
    }
}
