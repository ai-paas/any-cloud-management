package com.aipaas.anycloud.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.aipaas.anycloud.domain.audit.AuditLogResponse;
import com.aipaas.anycloud.domain.cluster.api.response.UnifiedClusterResponse;
import com.aipaas.anycloud.domain.credential.CredentialHealth;
import com.aipaas.anycloud.domain.credential.api.response.CspCredentialResponse;
import com.aipaas.anycloud.domain.operation.OperationResponse;
import com.aipaas.anycloud.domain.provisioning.api.response.VmClusterListItemResponse;
import com.aipaas.anycloud.domain.provisioning.api.response.VmClusterStatusResponse;
import com.aipaas.anycloud.testsupport.AbstractUnitTest;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/**
 * 응답 필드 이름을 고정한다.
 *
 * <p>필드 이름 변경이나 삭제 시 화면이 무음으로 빈 값 표시. 해당 사례 3건 발생. 감사 로그의
 * 시각과 응답 코드 미표시(createdAt, statusCode 를 timestamp, status 로 조회), clusterId 가
 * DTO 에 부재해 프로비저닝과 클러스터 연결 불가.
 *
 * <p>이 이름을 변경하려면 소비자(ai-paas-web, ai-paas-gateway)도 함께 수정 필요.
 */
class ResponseFieldContractTest extends AbstractUnitTest {

    /** record 든 lombok 클래스든 선언 필드 이름이 곧 JSON 키다. */
    private Set<String> fieldsOf(Class<?> type) {
        return Arrays.stream(type.getDeclaredFields())
                .filter(f -> !f.isSynthetic())
                .filter(f -> !Modifier.isStatic(f.getModifiers()))
                .map(Field::getName)
                .collect(Collectors.toSet());
    }

    @Test
    void vmListItemKeepsTheFieldsTheListScreenReads() {
        assertThat(fieldsOf(VmClusterListItemResponse.class))
                .contains(
                        "id",
                        "clusterName",
                        "clusterProvider",
                        "status",
                        "currentWorkflowStep",
                        "region",
                        "environment",
                        "clusterId",
                        "clusterRegistered",
                        "createdAt");
    }

    @Test
    void vmStatusKeepsTheFieldsTheDetailScreenReads() {
        assertThat(fieldsOf(VmClusterStatusResponse.class))
                .contains(
                        "clusterName",
                        "status",
                        "clusterId",
                        "apiServerUrl",
                        "masterPublicIp",
                        "nodes",
                        "requestedAt",
                        "provisioningStartedAt",
                        "bootstrappingStartedAt",
                        "verifyingStartedAt",
                        "readyAt");
    }

    @Test
    void unifiedClusterKeepsSourcesForTheBadge() {
        assertThat(fieldsOf(UnifiedClusterResponse.class))
                .contains("source", "sources", "clusterName", "provider", "status", "agentConnectivity");
    }

    @Test
    void operationKeepsTheFieldsTheHistoryScreenReads() {
        assertThat(fieldsOf(OperationResponse.class))
                .contains(
                        "id",
                        "type",
                        "resourceType",
                        "resourceId",
                        "state",
                        "progress",
                        "errorMessage",
                        "request",
                        "startedAt",
                        "endedAt");
    }

    @Test
    void auditLogUsesCreatedAtAndStatusCode() {
        Set<String> fields = fieldsOf(AuditLogResponse.class);

        assertThat(fields).contains("createdAt", "statusCode", "requestId", "principal", "httpMethod", "path");
        assertThat(fields).doesNotContain("timestamp", "status");
    }

    @Test
    void credentialNeverExposesTheValues() {
        Set<String> fields = fieldsOf(CspCredentialResponse.class);

        assertThat(fields).contains("id", "provider", "name", "credentialKeys", "healthStatus", "healthCheckedAt");
        assertThat(fields).doesNotContain("credentials", "encryptedPayload", "payload");
    }

    @Test
    void credentialHealthCarriesTheActionableFields() {
        assertThat(fieldsOf(CredentialHealth.class)).contains("healthy", "kind", "hint", "detail", "checkedAt");
    }
}
