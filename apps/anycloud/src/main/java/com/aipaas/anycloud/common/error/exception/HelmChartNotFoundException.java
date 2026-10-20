package com.aipaas.anycloud.common.error.exception;

import com.aipaas.anycloud.common.error.enums.ErrorCode;

/** Helm chart 를 찾지 못했습니다. */
public class HelmChartNotFoundException extends CustomException {

    public HelmChartNotFoundException(String chartName) {
        super("Helm chart not found: " + chartName, ErrorCode.ENTITY_NOT_FOUND);
    }

    public HelmChartNotFoundException(String repositoryName, String chartName) {
        super("Helm chart not found: " + repositoryName + "/" + chartName, ErrorCode.ENTITY_NOT_FOUND);
    }
}
