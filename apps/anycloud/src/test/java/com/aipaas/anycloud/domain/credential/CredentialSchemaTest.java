package com.aipaas.anycloud.domain.credential;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import com.aipaas.anycloud.domain.credential.api.CredentialFieldSchema;
import com.aipaas.anycloud.domain.provisioning.model.SupportedProvisioningProvider;
import com.aipaas.anycloud.domain.provisioning.preflight.validation.ProvisioningCredentialRules;
import com.aipaas.anycloud.testsupport.AbstractUnitTest;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * 자격증명 입력 화면이 프로바이더마다 무엇을 물어야 하는지.
 *
 * <p>지금은 사용자가 KEY=VALUE 를 직접 타이핑한다. 키 이름을 알아야 쓸 수 있고, 오타는 등록한 뒤
 * 프로비저닝이 실패해야 드러난다.
 */
class CredentialSchemaTest extends AbstractUnitTest {

    private List<CredentialFieldSchema> schemaOf(SupportedProvisioningProvider provider) {
        return CredentialSchema.of(provider);
    }

    private Set<String> keysOf(SupportedProvisioningProvider provider) {
        return schemaOf(provider).stream().map(CredentialFieldSchema::key).collect(Collectors.toSet());
    }

    @ParameterizedTest
    @EnumSource(SupportedProvisioningProvider.class)
    void everyProviderCanBeFilledInWithoutKnowingKeyNames(SupportedProvisioningProvider provider) {
        assertThat(schemaOf(provider)).isNotEmpty();
        assertThat(schemaOf(provider)).allSatisfy(field -> {
            assertThat(field.key()).isNotBlank();
            assertThat(field.label()).isNotBlank();
        });
    }

    @ParameterizedTest
    @EnumSource(SupportedProvisioningProvider.class)
    void aFullyFilledFormPassesProvisioningValidation(SupportedProvisioningProvider provider) {
        /*
         * 키 목록을 그대로 비교하면 택일 관계를 담지 못한다 — OCI 는 개인키 본문이나 경로 중
         * 하나면 되는데, 목록 비교는 둘 다 화면에 있어야 한다고 우긴다.
         * 화면을 다 채웠을 때 검증을 통과하는지가 진짜 계약이다.
         */
        Map<String, String> filled =
                schemaOf(provider).stream().collect(Collectors.toMap(CredentialFieldSchema::key, f -> "x"));

        assertThatCode(() -> ProvisioningCredentialRules.validateCredentialValues(provider, filled))
                .doesNotThrowAnyException();
    }

    @ParameterizedTest
    @EnumSource(SupportedProvisioningProvider.class)
    void theFormNeverAsksForABackendFilePath(SupportedProvisioningProvider provider) {
        // 포탈 사용자는 백엔드 파일시스템을 모른다. 경로를 물으면 채울 수 없는 칸이 된다.
        assertThat(keysOf(provider))
                .as("%s", provider)
                .noneMatch(key -> key.toLowerCase().endsWith("_path") || key.equals("GOOGLE_APPLICATION_CREDENTIALS"));
    }

    @Test
    void secretsAreMarkedSoTheScreenCanHideThem() {
        assertThat(schemaOf(SupportedProvisioningProvider.AWS))
                .filteredOn(f -> "AWS_SECRET_ACCESS_KEY".equals(f.key()))
                .singleElement()
                .satisfies(f -> assertThat(f.secret()).isTrue());

        assertThat(schemaOf(SupportedProvisioningProvider.AWS))
                .filteredOn(f -> "AWS_ACCESS_KEY_ID".equals(f.key()))
                .singleElement()
                .satisfies(f -> assertThat(f.secret()).isFalse());
    }

    @Test
    void keyMaterialGetsARoomyBox() {
        // GCP 서비스 계정 JSON 과 OCI 개인키는 한 줄짜리 칸에 넣을 수 없다.
        assertThat(schemaOf(SupportedProvisioningProvider.GCP))
                .filteredOn(f -> "GOOGLE_CREDENTIALS".equals(f.key()))
                .singleElement()
                .satisfies(f -> assertThat(f.multiline()).isTrue());
    }

    @Test
    void eitherOrFieldsShareAGroupSoOnlyOneIsDemanded() {
        // DigitalOcean 은 토큰 키 이름이 둘이고 하나만 있으면 된다. 둘 다 필수로 물으면 같은 값을
        // 두 번 넣게 된다.
        List<CredentialFieldSchema> digitalOcean = schemaOf(SupportedProvisioningProvider.DIGITALOCEAN);

        assertThat(digitalOcean)
                .filteredOn(f -> f.key().startsWith("DIGITALOCEAN_"))
                .allSatisfy(f -> assertThat(f.required()).isFalse())
                .extracting(CredentialFieldSchema::group)
                .containsOnly("token");
    }

    @Test
    void keyMaterialIsAskedForInlineNotAsAPath() {
        // 포탈에서 백엔드 파일시스템 경로를 알 방법이 없다. 본문을 붙여넣게 한다.
        assertThat(keysOf(SupportedProvisioningProvider.GCP)).containsExactly("GOOGLE_CREDENTIALS");
        assertThat(keysOf(SupportedProvisioningProvider.OCI)).contains("TF_VAR_private_key");
    }

    @Test
    void openstackAsksForTheJumpHostToo() {
        // 사설망 OpenStack 은 점프 없이는 노드에 닿지 않는다. 자격증명에 딸려 있어야 한다.
        assertThat(keysOf(SupportedProvisioningProvider.OPENSTACK)).contains("SSH_JUMP_HOST", "SSH_JUMP_USER");
    }

    @Test
    void theJumpPasswordIsTreatedAsASecret() {
        assertThat(schemaOf(SupportedProvisioningProvider.OPENSTACK))
                .filteredOn(f -> "SSH_JUMP_PASSWORD".equals(f.key()))
                .singleElement()
                .satisfies(f -> assertThat(f.secret()).isTrue());
    }

    @Test
    void optionalExtrasAreNotDemanded() {
        // 점프는 필요한 사람만 쓴다. 필수로 물으면 공인망 OpenStack 을 등록할 수 없다.
        assertThat(schemaOf(SupportedProvisioningProvider.OPENSTACK))
                .filteredOn(f -> f.key().startsWith("SSH_JUMP_"))
                .allSatisfy(f -> assertThat(f.required()).isFalse());
    }

    @ParameterizedTest
    @EnumSource(SupportedProvisioningProvider.class)
    void everyFieldShowsWhatTheValueLooksLike(SupportedProvisioningProvider provider) {
        // 라벨만으로는 무엇을 넣어야 할지 모른다. OCID 인지 이메일인지 URL 인지는 예시가 말해준다.
        assertThat(schemaOf(provider)).allSatisfy(field -> assertThat(field.placeholder())
                .as("%s 의 %s 에 예시가 없다", provider, field.key())
                .isNotBlank());
    }

    @Test
    void examplesDoNotCarryRealSecrets() {
        // 예시를 그대로 복사해 쓰는 사람이 있다. 진짜처럼 보이는 값을 넣으면 안 된다.
        assertThat(schemaOf(SupportedProvisioningProvider.AWS))
                .filteredOn(f -> "AWS_SECRET_ACCESS_KEY".equals(f.key()))
                .singleElement()
                .satisfies(f -> assertThat(f.placeholder()).containsIgnoringCase("example"));
    }
}
