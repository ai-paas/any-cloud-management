package com.aipaas.anycloud.domain.provisioning;

import static org.assertj.core.api.Assertions.assertThat;

import com.aipaas.anycloud.testsupport.AbstractUnitTest;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/**
 * 처리 중 소유권을 잡는 조건.
 *
 * <p>AMQP 재전달은 <b>동일 messageId</b> 사용. 조건에 {@code processingMessageId = :messageId}
 * 가 있으면 재전달이 자기 락을 재획득해 동일 작업이 중복 실행. 30분 주기로 부트스트랩이 처음부터
 * 재시작하는 사례 관측.
 *
 * <p>크래시 후 재시도는 stale 창이 처리. messageId 일치만으로는 재진입 불허.
 */
class AcquireProcessingQueryTest extends AbstractUnitTest {

    private static final Path REPOSITORY =
            Path.of("src/main/java/com/aipaas/anycloud/domain/provisioning/VmClusterRepository.java");

    /**
     * WHERE 절만 검사. SET 절에도 {@code v.processingMessageId = :messageId} 가 있어 쿼리
     * 전체를 검사하면 수정 후에도 매칭.
     */
    private String acquireWhereClause() throws IOException {
        String src = Files.readString(REPOSITORY);
        int at = src.indexOf("int acquireProcessing(");
        assertThat(at).as("acquireProcessing 이 없다").isGreaterThan(0);
        String query = src.substring(src.lastIndexOf("@Query", at), at);
        int where = query.indexOf("where v.id = :id");
        assertThat(where).as("where 절을 찾지 못했다").isGreaterThan(0);
        return query.substring(where);
    }

    @Test
    void sameMessageIdDoesNotReacquireWhileSomeoneIsStillWorking() throws IOException {
        assertThat(acquireWhereClause()).doesNotContain("v.processingMessageId = :messageId");
    }

    @Test
    void anUnheldClusterCanBeAcquired() throws IOException {
        assertThat(acquireWhereClause()).contains("v.processingMessageId is null");
    }

    @Test
    void aStaleHolderIsTakenOver() throws IOException {
        // 크래시로 락이 잔존하면 영구 차단. 오래된 항목은 회수.
        assertThat(acquireWhereClause()).contains("v.processingStartedAt < :staleBefore");
    }
}
