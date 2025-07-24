package com.aipaas.anycloud.model.enums;

import com.fasterxml.jackson.annotation.JsonFormat;
import io.fabric8.kubernetes.api.model.HasMetadata;
import io.fabric8.kubernetes.client.KubernetesClient;
import java.util.List;
import java.util.function.BiFunction;
import lombok.Getter;
import lombok.ToString;

@Getter
@ToString
@JsonFormat(shape = JsonFormat.Shape.OBJECT)
public enum ResourceType {
	// APPS
	DAEMON_SET("daemonSets", true,
		(client, ns) -> {
			if (ns == null) {
				return client.apps().daemonSets().inAnyNamespace().list().getItems();
			}
			return client.apps().daemonSets().inNamespace(ns).list().getItems();
		},
		(client, nameNs) -> client.apps().daemonSets().inNamespace(nameNs[0]).withName(nameNs[1])
			.get(),
		(client, nameNs) -> !client.apps().daemonSets().inNamespace(nameNs[0]).withName(nameNs[1])
			.delete().isEmpty()),

	DEPLOYMENT("deployments", true,
		(client, ns) -> {
			if (ns == null) {
				return client.apps().deployments().inAnyNamespace().list().getItems();
			}
			return client.apps().deployments().inNamespace(ns).list().getItems();
		},
		(client, nameNs) -> client.apps().deployments().inNamespace(nameNs[0]).withName(nameNs[1])
			.get(),
		(client, nameNs) -> !client.apps().deployments().inNamespace(nameNs[0]).withName(nameNs[1])
			.delete().isEmpty()),

	REPLICA_SET("replicaSets", true,
		(client, ns) -> {
			if (ns == null) {
				return client.apps().replicaSets().inAnyNamespace().list().getItems();
			}
			return client.apps().replicaSets().inNamespace(ns).list().getItems();
		},
		(client, nameNs) -> client.apps().replicaSets().inNamespace(nameNs[0]).withName(nameNs[1])
			.get(),
		(client, nameNs) -> !client.apps().replicaSets().inNamespace(nameNs[0]).withName(nameNs[1])
			.delete().isEmpty()),

	STATEFUL_SET("statefulSets", true,
		(client, ns) -> {
			if (ns == null) {
				return client.apps().statefulSets().inAnyNamespace().list().getItems();
			}
			return client.apps().statefulSets().inNamespace(ns).list().getItems();
		},
		(client, nameNs) -> client.apps().statefulSets().inNamespace(nameNs[0]).withName(nameNs[1])
			.get(),
		(client, nameNs) -> !client.apps().statefulSets().inNamespace(nameNs[0]).withName(nameNs[1])
			.delete().isEmpty()),

	// BATCH
	JOB("jobs", true,
		(client, ns) -> {
			if (ns == null) {
				return client.batch().v1().jobs().inAnyNamespace().list().getItems();
			}
			return client.batch().v1().jobs().inNamespace(ns).list().getItems();
		},
		(client, nameNs) -> client.batch().v1().jobs().inNamespace(nameNs[0]).withName(nameNs[1])
			.get(),
		(client, nameNs) -> !client.batch().v1().jobs().inNamespace(nameNs[0]).withName(nameNs[1])
			.delete().isEmpty()),

	CRONJOB("cronJobs", true,
		(client, ns) -> {
			if (ns == null) {
				return client.batch().v1().cronjobs().inAnyNamespace().list().getItems();
			}
			return client.batch().v1().cronjobs().inNamespace(ns).list().getItems();
		},
		(client, nameNs) -> client.batch().v1().cronjobs().inNamespace(nameNs[0])
			.withName(nameNs[1]).get(),
		(client, nameNs) -> !client.batch().v1().cronjobs().inNamespace(nameNs[0])
			.withName(nameNs[1]).delete().isEmpty()),

	// CORE
	ENDPOINT("endpoints", true,
		(client, ns) -> {
			if (ns == null) {
				return client.endpoints().inAnyNamespace().list().getItems();
			}
			return client.endpoints().inNamespace(ns).list().getItems();
		},
		(client, nameNs) -> client.endpoints().inNamespace(nameNs[0]).withName(nameNs[1]).get(),
		(client, nameNs) -> !client.endpoints().inNamespace(nameNs[0]).withName(nameNs[1]).delete()
			.isEmpty()),

	NAMESPACE("namespaces", false,
		(client, ns) -> client.namespaces().list().getItems(),
		(client, nameNs) -> client.namespaces().withName(nameNs[1]).get(),
		(client, nameNs) -> !client.namespaces().withName(nameNs[1]).delete().isEmpty()),

	NODE("nodes", false,
		(client, ns) -> client.nodes().list().getItems(),
		(client, nameNs) -> client.nodes().withName(nameNs[1]).get(),
		(client, nameNs) -> !client.nodes().withName(nameNs[1]).delete().isEmpty()),

	PERSISTENT_VOLUME("persistentVolumes", false,
		(client, ns) -> client.persistentVolumes().list().getItems(),
		(client, nameNs) -> client.persistentVolumes().withName(nameNs[1]).get(),
		(client, nameNs) -> !client.persistentVolumes().withName(nameNs[1]).delete().isEmpty()),

	PERSISTENT_VOLUME_CLAIM("persistentVolumeClaims", true,
		(client, ns) -> {
			if (ns == null) {
				return client.persistentVolumeClaims().inAnyNamespace().list().getItems();
			}
			return client.persistentVolumeClaims().inNamespace(ns).list().getItems();
		},
		(client, nameNs) -> client.persistentVolumeClaims().inNamespace(nameNs[0])
			.withName(nameNs[1]).get(),
		(client, nameNs) -> !client.persistentVolumeClaims().inNamespace(nameNs[0])
			.withName(nameNs[1]).delete().isEmpty()),

	POD("pods", true,
		(client, ns) -> {
			if (ns == null) {
				return client.pods().inAnyNamespace().list().getItems();
			}
			return client.pods().inNamespace(ns).list().getItems();
		},
		(client, nameNs) -> client.pods().inNamespace(nameNs[0]).withName(nameNs[1]).get(),
		(client, nameNs) -> !client.pods().inNamespace(nameNs[0]).withName(nameNs[1]).delete()
			.isEmpty()),

	SECRET("secrets", true,
		(client, ns) -> {
			if (ns == null) {
				return client.secrets().inAnyNamespace().list().getItems();
			}
			return client.secrets().inNamespace(ns).list().getItems();
		},
		(client, nameNs) -> client.secrets().inNamespace(nameNs[0]).withName(nameNs[1]).get(),
		(client, nameNs) -> !client.secrets().inNamespace(nameNs[0]).withName(nameNs[1]).delete()
			.isEmpty()),

	SERVICE("services", true,
		(client, ns) -> {
			if (ns == null) {
				return client.services().inAnyNamespace().list().getItems();
			}
			return client.services().inNamespace(ns).list().getItems();
		},
		(client, nameNs) -> client.services().inNamespace(nameNs[0]).withName(nameNs[1]).get(),
		(client, nameNs) -> !client.services().inNamespace(nameNs[0]).withName(nameNs[1]).delete()
			.isEmpty()),

	SERVICE_ACCOUNT("serviceAccounts", true,
		(client, ns) -> {
			if (ns == null) {
				return client.serviceAccounts().inAnyNamespace().list().getItems();
			}
			return client.serviceAccounts().inNamespace(ns).list().getItems();
		},
		(client, nameNs) -> client.serviceAccounts().inNamespace(nameNs[0]).withName(nameNs[1])
			.get(),
		(client, nameNs) -> !client.serviceAccounts().inNamespace(nameNs[0]).withName(nameNs[1])
			.delete().isEmpty()),

	CONFIGMAP("configMaps", true,
		(client, ns) -> {
			if (ns == null) {
				return client.configMaps().inAnyNamespace().list().getItems();
			}
			return client.configMaps().inNamespace(ns).list().getItems();
		},
		(client, nameNs) -> client.configMaps().inNamespace(nameNs[0]).withName(nameNs[1]).get(),
		(client, nameNs) -> !client.configMaps().inNamespace(nameNs[0]).withName(nameNs[1]).delete()
			.isEmpty()),

	EVENT("events", true,
		(client, ns) -> {
			if (ns == null) {
				return client.v1().events().inAnyNamespace().list().getItems();
			}
			return client.v1().events().inNamespace(ns).list().getItems();
		},
		(client, nameNs) -> client.v1().events().inNamespace(nameNs[0]).withName(nameNs[1]).get(),
		(client, nameNs) -> !client.v1().events().inNamespace(nameNs[0]).withName(nameNs[1])
			.delete().isEmpty()),

	// RBAC
	ROLE("roles", true,
		(client, ns) -> {
			if (ns == null) {
				return client.rbac().roles().inAnyNamespace().list().getItems();
			}
			return client.rbac().roles().inNamespace(ns).list().getItems();
		},
		(client, nameNs) -> client.rbac().roles().inNamespace(nameNs[0]).withName(nameNs[1]).get(),
		(client, nameNs) -> !client.rbac().roles().inNamespace(nameNs[0]).withName(nameNs[1])
			.delete().isEmpty()),

	ROLE_BINDING("roleBindings", true,
		(client, ns) -> {
			if (ns == null) {
				return client.rbac().roleBindings().inAnyNamespace().list().getItems();
			}
			return client.rbac().roleBindings().inNamespace(ns).list().getItems();
		},
		(client, nameNs) -> client.rbac().roleBindings().inNamespace(nameNs[0]).withName(nameNs[1])
			.get(),
		(client, nameNs) -> !client.rbac().roleBindings().inNamespace(nameNs[0]).withName(nameNs[1])
			.delete().isEmpty()),

	CLUSTER_ROLE("clusterRoles", false,
		(client, ns) -> client.rbac().clusterRoles().list().getItems(),
		(client, nameNs) -> client.rbac().clusterRoles().withName(nameNs[1]).get(),
		(client, nameNs) -> !client.rbac().clusterRoles().withName(nameNs[1]).delete().isEmpty()),

	CLUSTER_ROLE_BINDING("clusterRoleBindings", false,
		(client, ns) -> client.rbac().clusterRoleBindings().list().getItems(),
		(client, nameNs) -> client.rbac().clusterRoleBindings().withName(nameNs[1]).get(),
		(client, nameNs) -> !client.rbac().clusterRoleBindings().withName(nameNs[1]).delete()
			.isEmpty()),

	// AUTOSCALING
	HPA("horizontalPodAutoscalers", true,
		(client, ns) -> {
			if (ns == null) {
				return client.autoscaling().v1().horizontalPodAutoscalers().inAnyNamespace().list()
					.getItems();
			}
			return client.autoscaling().v1().horizontalPodAutoscalers().inNamespace(ns).list()
				.getItems();
		},
		(client, nameNs) -> client.autoscaling().v1().horizontalPodAutoscalers()
			.inNamespace(nameNs[0]).withName(nameNs[1]).get(),
		(client, nameNs) -> !client.autoscaling().v1().horizontalPodAutoscalers()
			.inNamespace(nameNs[0]).withName(nameNs[1]).delete().isEmpty()),

	// NETWORK
	INGRESS("ingresses", true,
		(client, ns) -> {
			if (ns == null) {
				return client.network().v1().ingresses().inAnyNamespace().list().getItems();
			}
			return client.network().v1().ingresses().inNamespace(ns).list().getItems();
		},
		(client, nameNs) -> client.network().v1().ingresses().inNamespace(nameNs[0])
			.withName(nameNs[1]).get(),
		(client, nameNs) -> !client.network().v1().ingresses().inNamespace(nameNs[0])
			.withName(nameNs[1]).delete().isEmpty()),

	// STORAGE
	STORAGE_CLASS("storageClasses", false,
		(client, ns) -> client.storage().v1().storageClasses().list().getItems(),
		(client, nameNs) -> client.storage().v1().storageClasses().withName(nameNs[1]).get(),
		(client, nameNs) -> !client.storage().v1().storageClasses().withName(nameNs[1]).delete()
			.isEmpty());

	private final String kind;
	private final boolean namespaced;
	private final BiFunction<KubernetesClient, String, List<? extends HasMetadata>> fetcher;
	private final BiFunction<KubernetesClient, String[], ? extends HasMetadata> singleFetcher;
	private final BiFunction<KubernetesClient, String[], Boolean> singleDeleter;

	ResourceType(String kind,
		boolean namespaced,
		BiFunction<KubernetesClient, String, List<? extends HasMetadata>> fetcher,
		BiFunction<KubernetesClient, String[], ? extends HasMetadata> singleFetcher,
		BiFunction<KubernetesClient, String[], Boolean> singleDeleter) {
		this.kind = kind;
		this.namespaced = namespaced;
		this.fetcher = fetcher;
		this.singleFetcher = singleFetcher;
		this.singleDeleter = singleDeleter;
	}

	public static ResourceType fromKind(String kind) {
		for (ResourceType type : values()) {
			if (type.kind.equalsIgnoreCase(kind)) {
				return type;
			}
		}
		throw new IllegalArgumentException("Unknown kind: " + kind);
	}

	public List<? extends HasMetadata> getResources(KubernetesClient client, String namespace) {
		return fetcher.apply(client, namespaced ? namespace : null);
	}

	public HasMetadata getResourceByName(KubernetesClient client, String namespace, String name) {
		String ns = namespaced ? namespace : "default";
		return singleFetcher.apply(client, new String[]{ns, name});
	}

	public boolean deleteResource(KubernetesClient client, String namespace, String name) {
		String ns = namespaced ? namespace : "default";
		return singleDeleter.apply(client, new String[]{ns, name});
	}
}
