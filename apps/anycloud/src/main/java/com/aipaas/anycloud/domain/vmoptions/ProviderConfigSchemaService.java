package com.aipaas.anycloud.domain.vmoptions;

import com.aipaas.anycloud.domain.vmoptions.api.ProviderConfigKey;
import java.util.List;

/**
 * Provider 별 {@code spec.config} 에 들어갈 수 있는 모든 키의 schema 를 반환.
 *
 * <p>사용자가 "어떤 키를 보내야 하는지" 를 코드 안 보고도 알 수 있게 하는 발견성 endpoint.
 */
public interface ProviderConfigSchemaService {

    /**
     * 주어진 provider 의 config 키 schema 전체. cross-cutting + provider-specific 키 통합.
     *
     * @param provider canonical name 또는 alias (예: "AWS", "aws", "gcp").
     * @throws com.aipaas.anycloud.common.error.exception.CustomException provider 가 지원 목록에 없으면 400.
     */
    List<ProviderConfigKey> getSchema(String provider);
}
