package com.aipaas.anycloud.domain.operation.model;

/**
 * Long-Running Operation 의 타입. resource_type + type 조합이 의미 단위.
 */
public enum OperationType {

    // Cluster lifecycle
    CREATE_CLUSTER, // VM provision OR registered cluster import
    SCALE_CLUSTER, // workerCount 변경
    DELETE_CLUSTER, // destroy
    RETRY_WORKFLOW, // BLOCKED/FAILED 워크플로우 재시도
    RETRY_REGISTRATION, // kubeconfig 등록 재시도
    REFRESH_STATUS, // 등록된 cluster status 강제 갱신
    CONNECTIVITY_CHECK, // K8s API 연결 검증

    // Helm
    INSTALL_HELM_RELEASE,
    UPGRADE_HELM_RELEASE, // 3
    ROLLBACK_HELM_RELEASE,
    UNINSTALL_HELM_RELEASE,

    // Cluster addon.
    // install/uninstall 별도 type — operation list 필터링 / SSE 구분 / metric labeling 용도.
    INSTALL_ADDON,
    UNINSTALL_ADDON,

    // Workflow admin
    REPLAY_DEAD_LETTER_MESSAGE,
}
