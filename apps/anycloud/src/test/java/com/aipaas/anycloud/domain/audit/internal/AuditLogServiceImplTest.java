package com.aipaas.anycloud.domain.audit.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.aipaas.anycloud.domain.audit.AuditLogEntity;
import com.aipaas.anycloud.domain.audit.AuditLogRepository;
import com.aipaas.anycloud.domain.audit.AuditLogResponse;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

/**
 * {@link AuditLogServiceImpl} 회귀 lock —.
 *
 * <p>Audit log 는 compliance/incident investigation 의 ground truth — entity→DTO 매핑이 1 field 라도
 * 누락되면 incident 분석 시 정보 손실. 13 field 전체 매핑 + repository delegation 검증.
 */
class AuditLogServiceImplTest {

    private AuditLogRepository repository;
    private AuditLogServiceImpl service;

    @BeforeEach
    void setUp() {
        repository = Mockito.mock(AuditLogRepository.class);
        service = new AuditLogServiceImpl(repository);
    }

    @Test
    void search_delegatesAllParamsToRepository() {
        when(repository.search(any(), any(), any(), any(), any(), any(), any())).thenReturn(List.of());

        LocalDateTime since = LocalDateTime.of(2026, 6, 1, 0, 0);
        LocalDateTime until = LocalDateTime.of(2026, 6, 9, 23, 59);
        Pageable pageable = PageRequest.of(2, 50);

        service.search(since, until, "cluster", "orb-001", "DELETE", "alice@example.com", pageable);

        verify(repository)
                .search(
                        eq(since),
                        eq(until),
                        eq("cluster"),
                        eq("orb-001"),
                        eq("DELETE"),
                        eq("alice@example.com"),
                        eq(pageable));
    }

    @Test
    void search_mapsAllFieldsFromEntityToDto() {
        LocalDateTime created = LocalDateTime.of(2026, 6, 9, 14, 30, 15);
        AuditLogEntity entity = AuditLogEntity.builder()
                .id("evt-001")
                .requestId("req-abc-123")
                .principal("operator@innogrid.com")
                .clientIp("10.0.1.42")
                .httpMethod("POST")
                .path("/v1/clusters/orb-001/preflight")
                .action("CREATE")
                .resourceType("vmcluster")
                .resourceId("orb-001")
                .statusCode(201)
                .durationMs(842L)
                .errorMessage(null)
                .createdAt(created)
                .build();

        when(repository.search(any(), any(), any(), any(), any(), any(), any())).thenReturn(List.of(entity));

        List<AuditLogResponse> result = service.search(null, null, null, null, null, null, PageRequest.of(0, 10));

        assertThat(result).hasSize(1);
        AuditLogResponse dto = result.get(0);
        assertThat(dto.getId()).isEqualTo("evt-001");
        assertThat(dto.getRequestId()).isEqualTo("req-abc-123");
        assertThat(dto.getPrincipal()).isEqualTo("operator@innogrid.com");
        assertThat(dto.getClientIp()).isEqualTo("10.0.1.42");
        assertThat(dto.getHttpMethod()).isEqualTo("POST");
        assertThat(dto.getPath()).isEqualTo("/v1/clusters/orb-001/preflight");
        assertThat(dto.getAction()).isEqualTo("CREATE");
        assertThat(dto.getResourceType()).isEqualTo("vmcluster");
        assertThat(dto.getResourceId()).isEqualTo("orb-001");
        assertThat(dto.getStatusCode()).isEqualTo(201);
        assertThat(dto.getDurationMs()).isEqualTo(842L);
        assertThat(dto.getErrorMessage()).isNull();
        assertThat(dto.getCreatedAt()).isEqualTo(created);
    }

    @Test
    void search_preservesOrderingFromRepository() {
        AuditLogEntity e1 =
                AuditLogEntity.builder().id("first").action("CREATE").build();
        AuditLogEntity e2 =
                AuditLogEntity.builder().id("second").action("UPDATE").build();
        AuditLogEntity e3 =
                AuditLogEntity.builder().id("third").action("DELETE").build();

        when(repository.search(any(), any(), any(), any(), any(), any(), any())).thenReturn(List.of(e1, e2, e3));

        List<AuditLogResponse> result = service.search(null, null, null, null, null, null, PageRequest.of(0, 10));

        assertThat(result).extracting(AuditLogResponse::getId).containsExactly("first", "second", "third");
    }

    @Test
    void search_emptyResult_returnsEmptyList() {
        when(repository.search(any(), any(), any(), any(), any(), any(), any())).thenReturn(List.of());

        List<AuditLogResponse> result = service.search(null, null, null, null, null, null, PageRequest.of(0, 10));

        assertThat(result).isEmpty();
    }

    @Test
    void search_failedRequest_preservesErrorMessageAndStatusCode() {
        // 실 incident 시나리오 — 403/500 응답 entity 가 DTO 로 그대로 전달되어야 incident report 가 완전.
        AuditLogEntity entity = AuditLogEntity.builder()
                .id("evt-err")
                .action("DELETE")
                .statusCode(403)
                .errorMessage("AGENT_NAMESPACE_NOT_ALLOWED")
                .build();

        when(repository.search(any(), any(), any(), any(), any(), any(), any())).thenReturn(List.of(entity));

        List<AuditLogResponse> result = service.search(null, null, null, null, null, null, PageRequest.of(0, 10));

        assertThat(result.get(0).getStatusCode()).isEqualTo(403);
        assertThat(result.get(0).getErrorMessage()).isEqualTo("AGENT_NAMESPACE_NOT_ALLOWED");
    }
}
