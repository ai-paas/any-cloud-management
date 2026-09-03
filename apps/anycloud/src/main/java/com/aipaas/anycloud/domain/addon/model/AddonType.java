package com.aipaas.anycloud.domain.addon.model;

/**
 * Cluster addon 의 type — installer strategy dispatch 의 key.
 *
 * <p>각 type 별로 {@code AddonInstallerRegistry} 가 매칭되는 {@code AddonInstaller} bean 을
 * resolve. GENERIC 은 catalog YAML (Option B) 만으로 driven — chart_repo/chart_name/values 가
 * full spec 을 결정. 다른 type 들은 도메인-특화 logic 을 추가로 가진 installer (예: monitoring 은
 * GPU exporter 동반, velero 는 BackupPolicy 후속 install).
 */
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
