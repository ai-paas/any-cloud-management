package io.aipaas.cluster.provisioning.program;

/**
 * Cluster-wide port / 네트워크 상수. 모든 provider 에서 magic number 금지.
 * Go {@code infra/pulumi/pkg/model/constants.go} 등가물.
 */
public final class K8sConstants {

    /** kube-apiserver 의 default port (kubeadm). */
    public static final int PORT_KUBE_API_SERVER = 6443;
    /** SSH 표준 포트 — worker bootstrap 사용. */
    public static final int PORT_SSH = 22;
    /** Service NodePort 기본 범위. */
    public static final int NODE_PORT_MIN = 30000;
    public static final int NODE_PORT_MAX = 32767;
    /** etcd client port — HA control-plane 시. */
    public static final int PORT_ETCD_CLIENT = 2379;
    /** etcd peer-to-peer port. */
    public static final int PORT_ETCD_PEER = 2380;
    /** kubelet metric / log API. */
    public static final int PORT_KUBELET_API = 10250;

    private K8sConstants() {}
}
