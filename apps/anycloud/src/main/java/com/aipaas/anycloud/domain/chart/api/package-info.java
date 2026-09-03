/**
 * Chart 도메인의 REST API contract — Helm chart upload / metadata / release 의 HTTP wire format.
 *
 * <p>Sub-package:
 * <ul>
 *   <li>{@link com.aipaas.anycloud.domain.chart.api.request} — ChartDeployRequest</li>
 *   <li>{@link com.aipaas.anycloud.domain.chart.api.response} — *Response (Deploy/Detail/History
 *       /List/Readme/ReleaseResources/Releases/Status/Values)</li>
 *   <li>{@code api/} root — ChartHistoryItem, HelmReleaseResourceRef (특정 카테고리에 안 맞는
 *       wire DTO)</li>
 * </ul>
 */
package com.aipaas.anycloud.domain.chart.api;
