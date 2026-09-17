package com.aipaas.anycloud.domain.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.aipaas.anycloud.domain.audit.internal.AuditLogServiceImpl;
import com.aipaas.anycloud.testsupport.AbstractUnitTest;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

/**
 * 감사 로그는 태생적으로 무한히 쌓인다.
 *
 * <p>지금은 항상 첫 페이지만 조회해 100건 너머를 볼 방법이 없었다. 화면은 pageSize 를 보내는데
 * 백엔드는 limit 만 받아 그마저도 무시됐다.
 */
class AuditLogPaginationTest extends AbstractUnitTest {

    @Mock
    AuditLogRepository repository;

    @Test
    void passesThePageThrough() {
        when(repository.search(any(), any(), any(), any(), any(), any(), any())).thenReturn(new PageImpl<>(List.of()));
        AuditLogServiceImpl service = new AuditLogServiceImpl(repository);

        service.search(null, null, null, null, null, null, PageRequest.of(2, 50));

        ArgumentCaptor<Pageable> pageable = ArgumentCaptor.forClass(Pageable.class);
        verify(repository).search(any(), any(), any(), any(), any(), any(), pageable.capture());
        assertThat(pageable.getValue().getPageNumber()).isEqualTo(2);
        assertThat(pageable.getValue().getPageSize()).isEqualTo(50);
    }

    @Test
    void reportsTheTotalSoTheScreenCanPaginate() {
        // 총 건수를 모르면 화면이 마지막 페이지를 계산할 수 없다.
        when(repository.search(any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(new PageImpl<>(List.of(), PageRequest.of(0, 10), 1234));

        assertThat(new AuditLogServiceImpl(repository)
                        .search(null, null, null, null, null, null, PageRequest.of(0, 10))
                        .getTotalElements())
                .isEqualTo(1234);
    }
}
