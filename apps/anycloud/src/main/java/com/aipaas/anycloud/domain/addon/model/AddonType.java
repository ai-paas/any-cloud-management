package com.aipaas.anycloud.domain.addon.model;

/** Cluster addon 의 type — installer strategy dispatch 의 key. */
public enum AddonType {
    /** kube-prometheus-stack — 기존 ObservabilityStackInstaller 위임. */
    MONITORING,
    /** Velero — 기존 VeleroInstaller 위임. */
    VELERO,
    /** dcgm-exporter — GPU 노드 metric. monitoring 동반 또는 standalone. */
    GPU_EXPORTER,
    /** cert-manager — TLS 자동 발급. */
    CERT_MANAGER,
    /** ingress-nginx — Ingress controller. */
    INGRESS_NGINX,
    /** 그 외 helm chart — catalog 기반 generic install. */
    GENERIC;
}
