package com.aipaas.anycloud.domain.cluster.model;

import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/** CSP 별 GPU instance type 감지 — instance type 명 prefix 기반. */
public final class GpuInstanceClassifier {

    private GpuInstanceClassifier() {}

    private static final Pattern AWS = Pattern.compile("^(p\\d|g\\d|inf\\d|trn\\d).*");
    private static final Pattern GCP = Pattern.compile("^(a2-|a3-|g2-).*|.*-with-gpu");
    private static final Pattern OCI = Pattern.compile(".*\\.GPU.*", Pattern.CASE_INSENSITIVE);
    private static final Pattern ALIBABA = Pattern.compile("^ecs\\.(gn|ebmgn).*");

    /** IBM VPC 의 GPU 프로파일 — gx3-16x80x1l4, gx3d-160x1792x8h100. */
    private static final Pattern IBM = Pattern.compile("^gx\\d.*", Pattern.CASE_INSENSITIVE);

    /**
     * OpenStack 은 flavor 이름을 운영자가 정한다.
     *
     * <p>설치본마다 규칙이 달라 이름만으로는 확신할 수 없다 — 32-256-gpu, 16-64-gpu-2EA,
     * hybrid-mig-8-16-50-vgpu2 가 모두 같은 클라우드에 있다. 카탈로그의 gpuCount 가 1차이고
     * 이건 그게 없을 때의 보조다.
     */
    private static final List<String> OPENSTACK_MARKERS = List.of("gpu", "mig");

    /** 부정형 이름 — test-novgpu-2-4-20 은 GPU 가 없다는 뜻이다. */
    private static final List<String> OPENSTACK_NEGATED = List.of("nogpu", "novgpu", "non-gpu");

    public static boolean isGpu(String provider, String instanceType) {
        if (provider == null || instanceType == null || instanceType.isBlank()) return false;
        String type = instanceType.trim();
        return switch (provider.toLowerCase()) {
            case "aws" -> AWS.matcher(type).matches();
            case "gcp" -> GCP.matcher(type).matches();
            case "oci" -> OCI.matcher(type).matches();
            case "alibaba" -> ALIBABA.matcher(type).matches();
            case "ibm" -> IBM.matcher(type).matches();
            case "openstack" -> isOpenStackGpuFlavor(type);
            default -> false;
        };
    }

    /*
     * 정규식이 아니라 부분 문자열로 본다. 앞뒤 .* 로 감싼 대안 패턴은 이름이 길어질수록
     * 매칭 비용이 제곱으로 늘어(polynomial ReDoS), flavor 이름을 외부에서 받는 자리에서는
     * 쓸 수 없다.
     */
    private static boolean isOpenStackGpuFlavor(String type) {
        String lower = type.toLowerCase(Locale.ROOT);
        for (String negated : OPENSTACK_NEGATED) {
            if (lower.contains(negated)) return false;
        }
        for (String marker : OPENSTACK_MARKERS) {
            if (lower.contains(marker)) return true;
        }
        return false;
    }
}
