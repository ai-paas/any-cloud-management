package com.aipaas.anycloud.domain.cluster.model;

import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/** CSP 별 GPU instance type 감지: instance type 명 prefix 기반. */
public final class GpuInstanceClassifier {

    private GpuInstanceClassifier() {}

    private static final Pattern AWS = Pattern.compile("^(p\\d|g\\d|inf\\d|trn\\d).*");
    private static final Pattern GCP = Pattern.compile("^(a2-|a3-|g2-).*|.*-with-gpu");
    /** OCI shape 은 BM.GPU4.8 처럼 .GPU 를 낀다. 패턴이 아니라 부분 문자열: 아래 OpenStack 과 같은 이유. */
    private static final String OCI_MARKER = ".gpu";

    private static final Pattern ALIBABA = Pattern.compile("^ecs\\.(gn|ebmgn).*");

    /** IBM VPC 의 GPU 프로파일: gx3-16x80x1l4, gx3d-160x1792x8h100. */
    private static final Pattern IBM = Pattern.compile("^gx\\d.*", Pattern.CASE_INSENSITIVE);

    /**
     * OpenStack 은 flavor 이름을 운영자가 정합니다.
     *
     * <p>설치본마다 규칙이 달라 이름만으로는 확신할 수 없습니다. 32-256-gpu, 16-64-gpu-2EA,
     * hybrid-mig-8-16-50-vgpu2 가 한 클라우드에 함께 있습니다. 판단의 1차 근거는 카탈로그의
     * gpuCount 이고 이 목록은 그 값이 없을 때의 보조입니다.
     */
    private static final List<String> OPENSTACK_MARKERS = List.of("gpu", "mig");

    /** 부정형 이름. test-novgpu-2-4-20 은 GPU 가 없다는 뜻입니다. */
    private static final List<String> OPENSTACK_NEGATED = List.of("nogpu", "novgpu", "non-gpu");

    public static boolean isGpu(String provider, String instanceType) {
        if (provider == null || instanceType == null || instanceType.isBlank()) return false;
        String type = instanceType.trim();
        return switch (provider.toLowerCase()) {
            case "aws" -> AWS.matcher(type).matches();
            case "gcp" -> GCP.matcher(type).matches();
            case "oci" -> type.toLowerCase(Locale.ROOT).contains(OCI_MARKER);
            case "alibaba" -> ALIBABA.matcher(type).matches();
            case "ibm" -> IBM.matcher(type).matches();
            case "openstack" -> isOpenStackGpuFlavor(type);
            default -> false;
        };
    }

    /*
     * 정규식이 아니라 부분 문자열로 봅니다. 앞뒤를 .* 로 감싼 대안 패턴은 이름이 길어질수록
     * 매칭 비용이 제곱으로 늘어(polynomial ReDoS), flavor 이름을 외부에서 받는 자리에는
     * 쓸 수 없습니다.
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
