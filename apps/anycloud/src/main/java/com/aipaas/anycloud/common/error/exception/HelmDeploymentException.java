package com.aipaas.anycloud.common.error.exception;

import com.aipaas.anycloud.common.error.enums.ErrorCode;

/** Helm chart 배포 실패. */
public class HelmDeploymentException extends CustomException {

    public HelmDeploymentException(String message) {
        super("Helm deployment failed: " + message, ErrorCode.INTERNAL_SERVER_ERROR);
    }

    public HelmDeploymentException(String message, Throwable cause) {
        super("Helm deployment failed: " + message, ErrorCode.INTERNAL_SERVER_ERROR);
        initCause(cause);
    }
}
