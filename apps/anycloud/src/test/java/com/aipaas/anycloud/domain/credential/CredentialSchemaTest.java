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
 * 프로바이더별 자격증명 입력 항목 검증.
 *
 * <p>현재는 사용자가 KEY=VALUE 를 직접 입력. 키 이름을 알아야 사용 가능하고, 오타는 등록 후
 * 프로비저닝 실패 시점에 드러남.
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
         * 키 목록 단순 비교는 택일 관계를 표현하지 못함. OCI 는 개인키 본문과 경로 중 하나면
         * 충분하나 목록 비교는 양쪽 모두를 요구. 실제 계약은 화면을 모두 채웠을 때 검증 통과
         * 여부.
         */
        Map<String, String> filled =
                schemaOf(provider).stream().collect(Collectors.toMap(CredentialFieldSchema::key, f -> "x"));

        assertThatCode(() -> ProvisioningCredentialRules.validateCredentialValues(provider, filled))
                .doesNotThrowAnyException();
    }

    @ParameterizedTest
    @EnumSource(SupportedProvisioningProvider.class)
    void theFormNeverAsksForABackendFilePath(SupportedProvisioningProvider provider) {
        // 포탈 사용자는 백엔드 파일시스템 접근 불가. 경로를 요구하면 입력 불가 항목이 됨.
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
        // GCP 서비스 계정 JSON 과 OCI 개인키는 한 줄 입력 칸에 수용 불가.
        assertThat(schemaOf(SupportedProvisioningProvider.GCP))
                .filteredOn(f -> "GOOGLE_CREDENTIALS".equals(f.key()))
                .singleElement()
                .satisfies(f -> assertThat(f.multiline()).isTrue());
    }

    @Test
    void keyMaterialIsAskedForInlineNotAsAPath() {
        // 포탈에서 백엔드 파일시스템 경로 확인 불가. 본문 붙여넣기 방식 사용.
        assertThat(keysOf(SupportedProvisioningProvider.GCP)).containsExactly("GOOGLE_CREDENTIALS");
        assertThat(keysOf(SupportedProvisioningProvider.OCI)).contains("TF_VAR_private_key");
    }

    @Test
    void openstackAsksForTheJumpHostToo() {
        // 사설망 OpenStack 은 점프 호스트 없이 노드 접근 불가. 자격증명에 포함 필요.
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
        // 점프 호스트는 선택 항목. 필수로 요구하면 공인망 OpenStack 등록 불가.
        assertThat(schemaOf(SupportedProvisioningProvider.OPENSTACK))
                .filteredOn(f -> f.key().startsWith("SSH_JUMP_"))
                .allSatisfy(f -> assertThat(f.required()).isFalse());
    }

    @ParameterizedTest
    @EnumSource(SupportedProvisioningProvider.class)
    void everyFieldShowsWhatTheValueLooksLike(SupportedProvisioningProvider provider) {
        // 라벨만으로는 입력 값 형식 판단 불가. OCID, 이메일, URL 구분은 예시가 제공.
        assertThat(schemaOf(provider)).allSatisfy(field -> assertThat(field.placeholder())
                .as("%s 의 %s 에 예시가 없다", provider, field.key())
                .isNotBlank());
    }

    @Test
    void examplesDoNotCarryRealSecrets() {
        // 예시를 그대로 복사해 사용하는 경우가 있어 실제 값처럼 보이는 값 사용 금지.
        assertThat(schemaOf(SupportedProvisioningProvider.AWS))
                .filteredOn(f -> "AWS_SECRET_ACCESS_KEY".equals(f.key()))
                .singleElement()
                .satisfies(f -> assertThat(f.placeholder()).containsIgnoringCase("example"));
    }
}
