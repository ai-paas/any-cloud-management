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
 * <p>필드 이름이 바뀌거나 사라지면 화면이 조용히 빈칸을 보여준다. 실제로 이렇게 세 번 막혔다 —
 * 감사 로그의 시각과 응답 코드가 안 나오던 것(createdAt/statusCode 를 timestamp/status 로 읽음),
 * clusterId 가 DTO 에 아예 없어 프로비저닝과 클러스터를 연결하지 못하던 것.
 *
 * <p>여기 이름을 바꾸려면 소비자(ai-paas-web, ai-paas-gateway)도 같이 고쳐야 한다는 뜻이다.
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
