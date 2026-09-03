package com.aipaas.anycloud.common.validation;

/**
 * 공통 Bean Validation 정규식/길이 상수. controller path/query parameter 및 DTO 필드에 일관되게
 * 적용하여 잘못된 입력이 서비스 레이어로 흘러가는 것을 차단.
 * <p>
 * 정규식은 보수적으로 잡고, 운영하면서 false-positive 발생 시 필요한 케이스만 확장.
 */
public final class ApiValidationConstants {

    /** K8s RFC 1123 label 형식 (소문자, 숫자, hyphen). 노드/리소스 이름 표준. */
    public static final String K8S_NAME_PATTERN = "^[a-z0-9]([-a-z0-9]*[a-z0-9])?$";

    public static final int K8S_NAME_MAX = 63;

    /**
     * K8s namespace path 값. RFC 1123 label 규칙 + 다음 sentinel 허용:
     * <ul>
     *   <li>{@code -} : K8s 컨벤션 — cluster-scoped kind 에서 ns 가 의미 없을 때.</li>
     *   <li>{@code _all} : 모든 namespace 조회 (all-namespaces).</li>
     * </ul>
     */
    public static final String NAMESPACE_PATTERN = "^(-|_all|[a-z0-9]([-a-z0-9]*[a-z0-9])?)$";

    public static final int NAMESPACE_MAX = K8S_NAME_MAX;

    /** clusterProvider 토큰 (예: aws, gcp, azure, alibaba, oci, digitalocean, openstack). */
    public static final String PROVIDER_PATTERN = "^[A-Za-z][A-Za-z0-9_-]{0,31}$";

    /** environment (dev, stage, prod, qa-1 등). 영숫자 + hyphen/underscore. */
    public static final String ENVIRONMENT_PATTERN = "^[A-Za-z0-9_-]{1,32}$";

    /** region 토큰 (ap-northeast-2, us-east-1, cn-shanghai 등). 영숫자 + hyphen. */
    public static final String REGION_PATTERN = "^[A-Za-z0-9-]{1,32}$";

    /** VmClusterStatus enum 값 (대문자 + underscore). */
    public static final String STATUS_PATTERN = "^[A-Z_]{1,32}$";

    /** K8s resource kind (pods, services, deployments, ...). 소문자 알파벳. */
    public static final String K8S_KIND_PATTERN = "^[a-z][a-z0-9]{0,49}$";

    /** credential ID — UUID 변형 또는 사람이 식별 가능한 prefix-postfix. */
    public static final String CREDENTIAL_ID_PATTERN = "^[A-Za-z0-9_-]{1,64}$";

    /** Operation ID — {@code op-<base32 12 char>} 형식. OperationServiceImpl#generateId 와 일치. */
    public static final String OPERATION_ID_PATTERN = "^op-[A-Za-z0-9]{1,32}$";

    /** description / 자유 텍스트 길이 상한. */
    public static final int DESCRIPTION_MAX = 512;

    private ApiValidationConstants() {}
}
