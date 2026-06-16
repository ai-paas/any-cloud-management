package com.aipaas.anycloud.service.provisioning;

import com.aipaas.anycloud.domain.provisioning.properties.PulumiBackupProperties;
import io.aipaas.cluster.provisioning.core.PulumiBackupPropertiesProvider;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * cluster-provisioning starter 의 {@link PulumiBackupPropertiesProvider} 포트를 anycloud 의
 * {@link PulumiBackupProperties}(prefix {@code pulumi.backup})로 연결하는 어댑터.
 *
 * <p>이 bean 으로 starter 의 backup scheduler/validator 가 anycloud 의 기존 {@code pulumi.backup.*}
 * 설정값을 그대로 사용한다 (값 config 키 변경 없음). cron schedule 만 starter property
 * {@code cluster-provisioning.state-backup.cron} (기본값 동일) 으로 조정된다.
 */
@Component
@RequiredArgsConstructor
public class AnycloudPulumiBackupPropertiesProvider implements PulumiBackupPropertiesProvider {

    private final PulumiBackupProperties properties;

    @Override
    public boolean isEnabled() {
        return properties.isEnabled();
    }

    @Override
    public String getDirectory() {
        return properties.getDirectory();
    }

    @Override
    public int getRetentionDays() {
        return properties.getRetentionDays();
    }

    @Override
    public boolean isRestoreDryRunEnabled() {
        return properties.getRestoreDryRun().isEnabled();
    }

    @Override
    public int getMinResourceCount() {
        return properties.getRestoreDryRun().getMinResourceCount();
    }

    @Override
    public boolean isDeepValidation() {
        return properties.getRestoreDryRun().isDeepValidation();
    }
}
