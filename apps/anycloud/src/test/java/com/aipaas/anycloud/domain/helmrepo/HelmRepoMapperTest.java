package com.aipaas.anycloud.domain.helmrepo;

import static org.assertj.core.api.Assertions.assertThat;

import com.aipaas.anycloud.domain.helmrepo.mapper.HelmRepoMapper;
import com.aipaas.anycloud.domain.helmrepo.model.HelmRepo;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;
import org.mapstruct.factory.Mappers;

/**
 * HelmRepoMapper 단위 테스트 — MapStruct instance ({@link Mappers#getMapper}) 호출 round-trip 검증.
 *
 * <p>검증: null-safe, round-trip, sensitive field 보존 (username/password 는 redaction 없음 — 그건
 * controller / DTO 책임).
 */
class HelmRepoMapperTest {

    private final HelmRepoMapper mapper = Mappers.getMapper(HelmRepoMapper.class);

    @Test
    void toDomain_nullEntity_returnsNull() {
        assertThat(mapper.toDomain(null)).isNull();
    }

    @Test
    void toEntity_nullDomain_returnsNull() {
        assertThat(mapper.toEntity(null)).isNull();
    }

    @Test
    void toDomain_fullEntity_mapsAllFields() {
        LocalDateTime now = LocalDateTime.of(2026, 6, 10, 12, 0);
        HelmRepoEntity e = HelmRepoEntity.builder()
                .id("repo-1")
                .name("bitnami")
                .url("https://charts.bitnami.com/bitnami")
                .username("u")
                .password("p")
                .caFile("---BEGIN CERT---")
                .insecureSkipTlsVerify(true)
                .build();
        e.setCreatedAt(now);
        e.setUpdatedAt(now);

        HelmRepo d = mapper.toDomain(e);

        assertThat(d.id()).isEqualTo("repo-1");
        assertThat(d.name()).isEqualTo("bitnami");
        assertThat(d.url()).isEqualTo("https://charts.bitnami.com/bitnami");
        assertThat(d.username()).isEqualTo("u");
        assertThat(d.password()).isEqualTo("p");
        assertThat(d.caFile()).isEqualTo("---BEGIN CERT---");
        assertThat(d.insecureSkipTlsVerify()).isTrue();
        assertThat(d.createdAt()).isEqualTo(now);
        assertThat(d.updatedAt()).isEqualTo(now);
    }

    @Test
    void roundTrip_preservesPersistableFields() {
        // JPA lifecycle 가 채우는 createdAt / updatedAt 은 mapper 의 entity 변환에서 제외 — domain 의 값은
        // "현재 상태 snapshot" 으로만 의미가 있다. 따라서 round-trip 비교는 persistable field 만.
        HelmRepo original = new HelmRepo(
                "id-1",
                "anycloud-internal",
                "https://chart.example.org",
                null,
                null,
                null,
                false,
                null,
                null,
                null,
                null);

        HelmRepoEntity entity = mapper.toEntity(original);
        HelmRepo restored = mapper.toDomain(entity);

        assertThat(restored.id()).isEqualTo(original.id());
        assertThat(restored.name()).isEqualTo(original.name());
        assertThat(restored.url()).isEqualTo(original.url());
        assertThat(restored.username()).isNull();
        assertThat(restored.password()).isNull();
        assertThat(restored.insecureSkipTlsVerify()).isFalse();
    }

    @Test
    void hasCredentials_truthful() {
        HelmRepo withCreds = new HelmRepo("1", "n", "u", "user", "pass", null, false, null, null, null, null);
        HelmRepo noCreds = new HelmRepo("2", "n", "u", null, null, null, false, null, null, null, null);
        HelmRepo blankCreds = new HelmRepo("3", "n", "u", "", "pass", null, false, null, null, null, null);

        assertThat(withCreds.hasCredentials()).isTrue();
        assertThat(noCreds.hasCredentials()).isFalse();
        assertThat(blankCreds.hasCredentials()).isFalse();
    }
}
