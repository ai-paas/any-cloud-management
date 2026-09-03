package com.aipaas.anycloud.domain.addon;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.persistence.Column;
import jakarta.persistence.Id;
import java.lang.reflect.Field;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * id 생성 규칙과 @Column(length) 가 어긋나지 않는지 확인.
 *
 * <p>"addon-" + UUID 는 42자인데 length 가 36 이어서, ddl-auto 로 스키마를 만드는 환경에서
 * varchar(36) 이 생성돼 addon 생성이 항상 500 으로 실패했다. Flyway 쪽만 64 로 고쳐져 있어
 * 운영에서는 드러나지 않았다.
 */
class ClusterAddonEntityIdLengthTest {

    private static final String PREFIX = "addon-";
    private static final int UUID_LENGTH = 36;

    @Test
    @DisplayName("@Column(length) 가 실제 생성되는 id 길이를 담을 수 있어야 한다")
    void idColumnLength_fitsGeneratedId() throws Exception {
        Field id = ClusterAddonEntity.class.getDeclaredField("id");
        assertThat(id.getAnnotation(Id.class)).as("id 필드는 @Id 여야 함").isNotNull();

        int declared = id.getAnnotation(Column.class).length();
        int generated = PREFIX.length() + UUID_LENGTH;

        assertThat(declared)
                .as("@Column(length=%d) 가 생성되는 id %d자를 담지 못한다", declared, generated)
                .isGreaterThanOrEqualTo(generated);
    }

    @Test
    @DisplayName("생성 규칙이 바뀌면 이 테스트가 먼저 깨지도록 길이를 고정한다")
    void generatedIdLength_isStable() {
        ClusterAddonEntity e = new ClusterAddonEntity();
        e.onCreate();
        assertThat(e.getId()).startsWith(PREFIX).hasSize(PREFIX.length() + UUID_LENGTH);
    }
}
