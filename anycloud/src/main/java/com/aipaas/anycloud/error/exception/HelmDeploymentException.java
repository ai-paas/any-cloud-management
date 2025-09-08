package com.aipaas.anycloud.error.exception;

import com.aipaas.anycloud.error.enums.ErrorCode;

/**
 * <pre>
 * ClassName : HelmDeploymentException
 * Type : class
 * Description : Helm Chart 배포 실패 시 발생하는 예외입니다.
 * Related : ChartService
 * </pre>
 */
public class HelmDeploymentException extends CustomException {

    public HelmDeploymentException(String message) {
        super("Helm deployment failed: " + message, ErrorCode.INTERNAL_SERVER_ERROR);
    }

    public HelmDeploymentException(String message, Throwable cause) {
        super("Helm deployment failed: " + message, ErrorCode.INTERNAL_SERVER_ERROR);
        initCause(cause);
    }
}
