package com.aipaas.anycloud.common.error.exception;

import com.aipaas.anycloud.common.error.enums.ErrorCode;

/** Helm repository 를 찾지 못했습니다. */
public class HelmRepositoryNotFoundException extends CustomException {

    public HelmRepositoryNotFoundException(String repositoryName) {
        super("Helm repository not found: " + repositoryName, ErrorCode.ENTITY_NOT_FOUND);
    }
}
