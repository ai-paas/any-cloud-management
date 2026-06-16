/**
 * Provisioning 도메인의 REST API contract — HTTP wire format.
 *
 * <p>외부 consumer (frontend, Bruno, OpenAPI) 가 직접 사용하는 type. 내부 도메인은
 * {@link com.aipaas.anycloud.domain.provisioning.model}.
 *
 * <p>Sub-package:
 * <ul>
 *   <li>{@link com.aipaas.anycloud.domain.provisioning.api.request} — *Request (incoming)</li>
 *   <li>{@link com.aipaas.anycloud.domain.provisioning.api.response} — *Response (outgoing)</li>
 * </ul>
 */
package com.aipaas.anycloud.domain.provisioning.api;
