package io.aipaas.cluster.provisioning.program;

import com.pulumi.Context;
import com.pulumi.core.Output;
import io.aipaas.cluster.provisioning.api.ProvisioningRequest;
import io.aipaas.cluster.provisioning.program.provisioner.ProviderProvisioner;
import io.aipaas.cluster.provisioning.program.provisioner.ProviderRegistry;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/** Pulumi 자동화 API 안의 Pulumi 프로그램. */
@Slf4j
@RequiredArgsConstructor
public class ProvisionerOrchestrator {

    private final ProviderRegistry registry;

    public void run(Context ctx, ProvisioningRequest request) {
        ClusterSpec spec = ClusterSpec.load(ctx).normalize();
        log.info(
                "ProvisionerOrchestrator: provider={} cluster={} masters={} workers={} k8s={}",
                spec.provider(),
                spec.name(),
                spec.masterCount(),
                spec.workerCount(),
                spec.kubernetesVersion());

        ProviderProvisioner provisioner = registry.get(spec.provider());
        Map<String, Output<?>> outputs = provisioner.provision(ctx, spec);
        outputs.forEach(ctx::export);

        ctx.export(
                "summary",
                Output.of("provider=" + spec.provider()
                        + " cluster=" + spec.name()
                        + " masters=" + spec.masterCount()
                        + " workers=" + spec.workerCount()
                        + " kubernetes=" + spec.kubernetesVersion()));
    }
}
