package com.aipaas.anycloud.service.util;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.springframework.stereotype.Component;
import com.aipaas.anycloud.configuration.bean.KubernetesClientConfig;
import com.aipaas.anycloud.model.entity.ClusterEntity;
import io.fabric8.kubernetes.api.model.HasMetadata;
import io.fabric8.kubernetes.client.KubernetesClient;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Component
public class HelmReleaseScanner {

  public List<HasMetadata> scanReleaseResources(ClusterEntity cluster, String namespace, String releaseName) {

    KubernetesClientConfig manager = new KubernetesClientConfig(cluster);
    KubernetesClient client = manager.getClient();
    List<HasMetadata> results = new ArrayList<>();

    // Helm이 기본적으로 생성할 가능성이 높은 리소스 타입들
    results.addAll(client.apps().deployments()
            .inNamespace(namespace)
            .withLabel("app.kubernetes.io/instance", releaseName)
            .list().getItems());

    results.addAll(client.apps().statefulSets()
            .inNamespace(namespace)
            .withLabel("app.kubernetes.io/instance", releaseName)
            .list().getItems());

    results.addAll(client.apps().daemonSets()
            .inNamespace(namespace)
            .withLabel("app.kubernetes.io/instance", releaseName)
            .list().getItems());

    results.addAll(client.services()
            .inNamespace(namespace)
            .withLabel("app.kubernetes.io/instance", releaseName)
            .list().getItems());

    results.addAll(client.configMaps()
            .inNamespace(namespace)
            .withLabel("app.kubernetes.io/instance", releaseName)
            .list().getItems());

    results.addAll(client.secrets()
            .inNamespace(namespace)
            .withLabel("app.kubernetes.io/instance", releaseName)
            .list().getItems());

    var pvcs = client.persistentVolumeClaims()
            .inNamespace(namespace)
            .withLabel("app.kubernetes.io/instance", releaseName)
            .list()
            .getItems();
    results.addAll(pvcs);

    if (!pvcs.isEmpty()) {
      pvcs.stream()
              .map(pvc -> pvc.getSpec() != null ? pvc.getSpec().getVolumeName() : null)
              .filter(Objects::nonNull)
              .map(pvName -> client.persistentVolumes().withName(pvName).get())
              .filter(Objects::nonNull)
              .forEach(results::add);
  }
    
    results.addAll(client.persistentVolumes()
            // .inNamespace(namespace)
            .withLabel("app.kubernetes.io/instance", releaseName)
            .list().getItems());

    results.addAll(client.rbac().roles()
            .inNamespace(namespace)
            .withLabel("app.kubernetes.io/instance", releaseName)
            .list().getItems());

    results.addAll(client.rbac().roleBindings()
            .inNamespace(namespace)
            .withLabel("app.kubernetes.io/instance", releaseName)
            .list().getItems());

    results.addAll(client.serviceAccounts()
            .inNamespace(namespace)
            .withLabel("app.kubernetes.io/instance", releaseName)
            .list().getItems());

    results.addAll(client.network().v1().ingresses()
            .inNamespace(namespace)
            .withLabel("app.kubernetes.io/instance", releaseName)
            .list().getItems());

    return results;
  }

}
