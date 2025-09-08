package com.aipaas.anycloud.error.exception;

import com.aipaas.anycloud.error.enums.ErrorCode;

/**
 * <pre>
 * ClassName : HelmRepositoryNotFoundException
 * Type : class
 * Description : Helm Repository를 찾을 수 없을 때 발생하는 예외입니다.
 * Related : ChartService
 * </pre>
 */
public class HelmRepositoryNotFoundException extends CustomException {

    public HelmRepositoryNotFoundException(String repositoryName) {
        super("Helm repository not found: " + repositoryName, ErrorCode.ENTITY_NOT_FOUND);
    }
}
