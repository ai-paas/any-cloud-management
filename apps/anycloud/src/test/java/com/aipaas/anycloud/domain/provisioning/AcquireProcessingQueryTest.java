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
 * <p>AMQP 재전달은 <b>같은 messageId</b> 를 쓴다. 조건에 {@code processingMessageId = :messageId}
 * 가 있으면 재전달이 자기 락을 다시 잡아 같은 작업이 두 번 돈다 — 실제로 30분마다 부트스트랩이
 * 처음부터 다시 시작하는 것을 관측했다.
 *
 * <p>크래시 후 재시도는 stale 창이 열어준다. 같은 messageId 라는 이유만으로 열어주면 안 된다.
 */
class AcquireProcessingQueryTest extends AbstractUnitTest {

    private static final Path REPOSITORY =
            Path.of("src/main/java/com/aipaas/anycloud/domain/provisioning/VmClusterRepository.java");

    /**
     * WHERE 절만 본다. SET 절에도 {@code v.processingMessageId = :messageId} 가 있어서
     * 쿼리 전체를 보면 고친 뒤에도 걸린다.
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
        // 크래시로 락이 남으면 영원히 막힌다. 오래된 것은 회수한다.
        assertThat(acquireWhereClause()).contains("v.processingStartedAt < :staleBefore");
    }
}
