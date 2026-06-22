package com.aipaas.anycloud.domain.cluster.model;

import java.util.regex.Pattern;

/**
 * CSP 별 GPU instance type 감지 — instance type 명 prefix 기반.
 *
 * <p>UI 가 보낸 {@code hasGpuNodes} 값과 별개로 server 가 instance type 으로 derive 해 둘을 OR — UI
 * 우회 / 누락 / 오등록 방어. web 의 {@code util/gpuInstance.ts} 와 동일 규칙 (single source of truth
 * 는 spec catalog 의 gpuCount 지만, 본 classifier 는 instance type 만 가지고 backup 감지).
 */
public final class GpuInstanceClassifier {

    private GpuInstanceClassifier() {}

    private static final Pattern AWS = Pattern.compile("^(p\\d|g\\d|inf\\d|trn\\d).*");
    private static final Pattern GCP = Pattern.compile("^(a2-|a3-|g2-).*|.*-with-gpu");
    private static final Pattern AZURE = Pattern.compile("^Standard_N[CDV].*", Pattern.CASE_INSENSITIVE);
    private static final Pattern OCI = Pattern.compile(".*\\.GPU.*", Pattern.CASE_INSENSITIVE);
    private static final Pattern ALIBABA = Pattern.compile("^ecs\\.(gn|ebmgn).*");
    private static final Pattern DO = Pattern.compile("^gpu-.*");

    public static boolean isGpu(String provider, String instanceType) {
        if (provider == null || instanceType == null || instanceType.isBlank()) return false;
        String type = instanceType.trim();
        return switch (provider.toLowerCase()) {
            case "aws" -> AWS.matcher(type).matches();
            case "gcp" -> GCP.matcher(type).matches();
            case "azure" -> AZURE.matcher(type).matches();
            case "oci" -> OCI.matcher(type).matches();
            case "alibaba" -> ALIBABA.matcher(type).matches();
            case "digitalocean" -> DO.matcher(type).matches();
            default -> false;
        };
    }
}
