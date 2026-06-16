package com.aipaas.anycloud.domain.provisioning.properties;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Pulumi state 자동 백업 설정.
 * <p>
 * Day-2 §6: state 손상 = 운영 사망. 정기 백업으로 복구 경로 확보.
 * <p>
 * 동작: cron 주기로 모든 활성 stack 에 대해 {@code pulumi stack export --file <directory>/<stack>-<ts>.json}
 * 을 실행. retention-days 보다 오래된 dump 는 삭제.
 *
 * <pre>
 * pulumi:
 *   backup:
 *     enabled: ${PULUMI_BACKUP_ENABLED:false}
 *     cron: ${PULUMI_BACKUP_CRON:"0 0 3 * * *"}    # 매일 03:00
 *     directory: ${PULUMI_BACKUP_DIR:/var/lib/anycloud/pulumi-backups}
 *     retention-days: ${PULUMI_BACKUP_RETENTION_DAYS:14}
 * </pre>
 */
@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "pulumi.backup")
public class PulumiBackupProperties {

    /** 활성 여부. dev 에선 false, 운영 환경에선 true 권장. */
    private boolean enabled = false;

    /** Spring cron 표현식 (6 필드: 초/분/시/일/월/요일). */
    private String cron = "0 0 3 * * *";

    /** 백업 파일 저장 디렉터리. PVC 또는 외부 storage 마운트 권장. */
    private String directory = "/var/lib/anycloud/pulumi-backups";

    /** 보존 기간(일). 초과한 파일은 자동 삭제. */
    private int retentionDays = 14;

    /** 백업 → 복구 dry-run 검증 설정. */
    private RestoreDryRun restoreDryRun = new RestoreDryRun();

    @Getter
    @Setter
    public static class RestoreDryRun {

        /**
         * 검증 cron 활성 여부. 권장: 백업 cron 보다 30분 뒤. dev 에선 false.
         */
        private boolean enabled = false;

        /**
         * 검증 cron. 백업 직후 실행하도록 백업 cron + 30분 정도 시차를 두는 것을 권장.
         */
        private String cron = "0 30 3 * * *";

        /**
         * 백업 파일에 존재해야 할 최소 resource 수. 0 이면 빈 stack 도 valid.
         * Pulumi 의 정상 state 는 보통 worker/master/security-group/eip 등 합쳐 10+ 이므로
         * 1 이상이면 충분.
         */
        private int minResourceCount = 1;

        /**
         * Deep validation: {@code pulumi stack import} 으로 임시 stack 에 실제 복원 시도.
         * Pulumi CLI 호출 비용이 있어 기본 false. true 면 {@code <stack>-restore-test} 스택을
         * 생성·import·삭제하여 라운드트립 검증.
         */
        private boolean deepValidation = false;
    }
}
