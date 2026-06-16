/**
 * Cluster 도메인의 REST API contract — HTTP wire format.
 *
 * <p>외부 consumer (frontend, Bruno, OpenAPI) 가 직접 사용하는 type. 변경 시 versioning + 외부
 * 영향 검토 필요. 내부 도메인 value object 는 {@link com.aipaas.anycloud.domain.cluster.model}.
 *
 * <p>Sub-package:
 * <ul>
 *   <li>{@link com.aipaas.anycloud.domain.cluster.api.request} — *Request, *Dto (incoming)</li>
 *   <li>{@link com.aipaas.anycloud.domain.cluster.api.response} — *Response (outgoing)</li>
 * </ul>
 */
package com.aipaas.anycloud.domain.cluster.api;
