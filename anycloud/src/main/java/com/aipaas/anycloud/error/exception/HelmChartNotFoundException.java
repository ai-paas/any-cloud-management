package com.aipaas.anycloud.error.exception;

import com.aipaas.anycloud.error.enums.ErrorCode;

/**
 * <pre>
 * ClassName : HelmChartNotFoundException
 * Type : class
 * Description : Helm Chart를 찾을 수 없을 때 발생하는 예외입니다.
 * Related : ChartService
 * </pre>
 */
public class HelmChartNotFoundException extends CustomException {

    public HelmChartNotFoundException(String chartName) {
        super("Helm chart not found: " + chartName, ErrorCode.ENTITY_NOT_FOUND);
    }

    public HelmChartNotFoundException(String repositoryName, String chartName) {
        super("Helm chart not found: " + repositoryName + "/" + chartName, ErrorCode.ENTITY_NOT_FOUND);
    }
}
