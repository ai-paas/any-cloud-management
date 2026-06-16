package com.aipaas.anycloud.domain.provisioning.bootstrap.providers;

import com.aipaas.anycloud.domain.provisioning.model.VmClusterInternalRequestSnapshot;

public abstract class PrivateLinuxVmClusterBootstrapStrategy extends GenericLinuxVmClusterBootstrapStrategy {

    @Override
    protected String ingressManifestUrl(VmClusterInternalRequestSnapshot snapshot) {
        return "https://raw.githubusercontent.com/kubernetes/ingress-nginx/controller-v1.11.1/deploy/static/provider/baremetal/deploy.yaml";
    }
}
