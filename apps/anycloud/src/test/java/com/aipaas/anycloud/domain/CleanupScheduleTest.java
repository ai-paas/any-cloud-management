package com.aipaas.anycloud.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.aipaas.anycloud.testsupport.AbstractIntegrationTest;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;

/**
 * 정리 스케줄러가 서로 겹치지 않는지.
 *
 * <p>정리 잡이 여섯 개인데 각자 cron 을 갖는다. ShedLock 은 같은 이름만 막아서 서로 다른 잡은
 * 같은 시각에 그대로 함께 돈다. 실제로 감사 로그와 작업 이력이 03:30 에 겹쳐 있었다.
 */
class CleanupScheduleTest extends AbstractIntegrationTest {

    @Value("${anycloud.audit.cleanup.cron}")
    String auditCron;

    @Value("${anycloud.operation.cleanup.cron}")
    String operationCron;

    @Value("${anycloud.vm-cluster.state-history.cleanup.cron}")
    String stateHistoryCron;

    @Value("${anycloud.bootstrap-jti.cleanup.cron}")
    String bootstrapJtiCron;

    @Value("${anycloud.vm-cluster.deleted.cleanup.cron}")
    String deletedVmCron;

    @Value("${anycloud.vm-cluster.deleted.retention-days}")
    int deletedVmRetentionDays;

    @Value("${anycloud.workflow-message-log.cleanup.cron}")
    String workflowMessageLogCron;

    @Value("${anycloud.workflow-message-log.retention-days}")
    int workflowMessageLogRetentionDays;

    private Map<String, String> crons() {
        Map<String, String> byName = new LinkedHashMap<>();
        byName.put("audit", auditCron);
        byName.put("operation", operationCron);
        byName.put("state-history", stateHistoryCron);
        byName.put("bootstrap-jti", bootstrapJtiCron);
        byName.put("deleted-vm", deletedVmCron);
        byName.put("workflow-message-log", workflowMessageLogCron);
        return byName;
    }

    @Test
    void noTwoCleanupJobsShareASlot() {
        Map<String, String> byName = crons();

        assertThat(byName.values()).as("같은 시각에 도는 정리 잡이 있다: %s", byName).doesNotHaveDuplicates();
    }

    @Test
    void cleanupRunsOutsideBusyHours() {
        // 낮에 돌면 목록 조회와 같은 테이블을 잠근다.
        crons().forEach((name, cron) -> {
            int hour = Integer.parseInt(cron.trim().split("\\s+")[2]);
            assertThat(hour).as("%s 가 업무 시간에 돈다 (cron=%s)", name, cron).isBetween(0, 5);
        });
    }

    @Test
    void deletedVmRetentionFollowsTheSharedConvention() {
        // 다른 정리 잡은 전부 retention-days(int) 사용. 단독 Duration 사용 시 운영자 혼동.
        assertThat(deletedVmRetentionDays).isEqualTo(90);
    }

    @Test
    void workflowMessageLogIsKeptLongerThanAnyRedelivery() {
        // 이 테이블은 멱등성 판정에도 사용. messageId 존재 시 재처리 차단. 보관 기간이
        // 재전달 창보다 짧으면 삭제 후 도착한 재전달이 동일 단계를 중복 실행.
        // AMQP ack 타임아웃이 30분이라 하루면 충분하나 실패 메시지 조회도 겸해 여유 설정.
        assertThat(workflowMessageLogRetentionDays).isGreaterThanOrEqualTo(7);
    }
}
