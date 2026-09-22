package com.aipaas.anycloud.domain.credential;

import com.aipaas.anycloud.domain.credential.api.CredentialFieldSchema;
import com.aipaas.anycloud.domain.provisioning.model.SupportedProvisioningProvider;
import java.util.List;

/**
 * 프로바이더마다 자격증명으로 무엇을 받아야 하는지.
 *
 * <p>화면이 KEY=VALUE 를 직접 받으면 사용자가 키 이름을 알아야 하고, 오타는 등록한 뒤 프로비저닝이
 * 실패해야 드러난다. 여기 목록이 입력 칸이 된다.
 *
 * <p>{@code ProvisioningCredentialRules.requiredCredentialKeys} 가 검증의 기준이고, 이 목록은 그것을
 * 모두 포함해야 한다 — 테스트가 그 관계를 고정한다.
 */
public final class CredentialSchema {

    /** Azure 식별자는 모두 UUID 모양이라 예시를 공유한다. */
    private static final String UUID_EXAMPLE = "00000000-0000-0000-0000-000000000000";

    private CredentialSchema() {}

    public static List<CredentialFieldSchema> of(SupportedProvisioningProvider provider) {
        return switch (provider) {
            case AWS -> List.of(
                    field(
                            "AWS_ACCESS_KEY_ID",
                            "액세스 키 ID",
                            true,
                            false,
                            false,
                            "IAM 사용자의 액세스 키",
                            "AKIAIOSFODNN7EXAMPLE"),
                    secret(
                            "AWS_SECRET_ACCESS_KEY",
                            "시크릿 액세스 키",
                            true,
                            "액세스 키와 짝이 되는 비밀값",
                            "wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY"));
            case GCP -> List.of(multiline(
                    "GOOGLE_CREDENTIALS",
                    "서비스 계정 JSON",
                    true,
                    "콘솔에서 내려받은 키 파일 내용을 그대로 붙여넣는다",
                    "{\"type\":\"service_account\",\"project_id\":\"my-project\", ...}"));
            case ALIBABA -> List.of(
                    field(
                            "ALICLOUD_ACCESS_KEY",
                            "액세스 키",
                            true,
                            false,
                            false,
                            "RAM 사용자의 액세스 키",
                            "LTAI5tEXAMPLEaccesskey"),
                    secret("ALICLOUD_SECRET_KEY", "시크릿 키", true, "액세스 키와 짝이 되는 비밀값", "EXAMPLEsecretkeyvalue"));
            case OPENSTACK -> openstack();
            case OCI -> List.of(
                    field(
                            "TF_VAR_tenancy_ocid",
                            "테넌시 OCID",
                            true,
                            false,
                            false,
                            null,
                            "ocid1.tenancy.oc1..aaaaaaaaexample"),
                    field("TF_VAR_user_ocid", "사용자 OCID", true, false, false, null, "ocid1.user.oc1..aaaaaaaaexample"),
                    field(
                            "TF_VAR_fingerprint",
                            "API 키 지문",
                            true,
                            false,
                            false,
                            "콘솔의 API 키 목록에 표시되는 값",
                            "aa:bb:cc:dd:ee:ff:00:11:22:33:44:55:66:77:88:99"),
                    field("TF_VAR_region", "리전", true, false, false, null, "ap-seoul-1"),
                    multiline(
                            "TF_VAR_private_key",
                            "API 개인키",
                            true,
                            "PEM 본문을 그대로 붙여넣는다",
                            "-----BEGIN PRIVATE KEY-----\nMIIEvQIB...\n-----END PRIVATE KEY-----"));
            case PROXMOX -> proxmox();
            case IBM -> List.of(
                    secret("IBMCLOUD_API_KEY", "API 키", true, "IAM 에서 발급한 API 키", "EXAMPLE-ibmcloud-api-key"));
        };
    }

    private static List<CredentialFieldSchema> openstack() {
        return List.of(
                field("OS_AUTH_URL", "인증 URL", true, false, false, null, "https://keystone.example.com:5000/v3"),
                field("OS_USERNAME", "사용자 이름", true, false, false, null, "admin"),
                secret("OS_PASSWORD", "비밀번호", true, null, "비밀번호"),
                field("OS_PROJECT_NAME", "프로젝트 이름", true, false, false, null, "admin"),
                // 사설망이면 점프 없이는 노드에 닿지 않는다. 공인망이면 비워 둔다.
                field("SSH_JUMP_HOST", "점프 호스트", false, false, false, "노드가 사설망일 때만. 비우면 직접 접속", "bastion.example.com"),
                field("SSH_JUMP_PORT", "점프 포트", false, false, false, "비우면 22", "22"),
                field("SSH_JUMP_USER", "점프 사용자", false, false, false, null, "ubuntu"),
                secret("SSH_JUMP_PASSWORD", "점프 비밀번호", false, "키 인증이면 비워 둔다", "비밀번호"));
    }

    private static List<CredentialFieldSchema> proxmox() {
        return List.of(
                field("PROXMOX_VE_ENDPOINT", "엔드포인트", true, false, false, null, "https://pve.example.com:8006"),
                // PVE 토큰 생성 화면이 두 값을 따로 보여준다. 한 칸으로 받으면 사용자가 손으로 잇는다.
                field(
                        "PROXMOX_VE_API_TOKEN_ID",
                        "토큰 ID",
                        true,
                        false,
                        false,
                        "PVE 토큰 생성 후 표시되는 Token ID",
                        "aipaas@pve!provisioner"),
                secret(
                        "PROXMOX_VE_API_TOKEN_SECRET",
                        "토큰 시크릿",
                        true,
                        "발급 시점에 한 번만 표시된다",
                        "00000000-0000-0000-0000-000000000000"),
                field("PROXMOX_VE_INSECURE", "인증서 검증 생략", false, false, false, "자체 서명 인증서면 true", "true"));
    }

    private static CredentialFieldSchema field(
            String key,
            String label,
            boolean required,
            boolean secret,
            boolean multiline,
            String description,
            String placeholder) {
        return CredentialFieldSchema.builder()
                .key(key)
                .label(label)
                .required(required)
                .secret(secret)
                .multiline(multiline)
                .description(description)
                .placeholder(placeholder)
                .build();
    }

    /** 그룹 없는 여러 줄 비밀값 — 키 본문이나 JSON 을 통째로 받는 자리. */
    private static CredentialFieldSchema multiline(
            String key, String label, boolean required, String description, String placeholder) {
        return field(key, label, required, true, true, description, placeholder);
    }

    private static CredentialFieldSchema secret(
            String key, String label, boolean required, String description, String placeholder) {
        return field(key, label, required, true, false, description, placeholder);
    }
}
