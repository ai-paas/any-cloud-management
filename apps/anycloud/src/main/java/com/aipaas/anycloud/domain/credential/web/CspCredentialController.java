package com.aipaas.anycloud.domain.credential.web;

import com.aipaas.anycloud.common.web.ActionResponse;
import com.aipaas.anycloud.common.web.ApiSuccessResponse;
import com.aipaas.anycloud.common.web.PagedData;
import com.aipaas.anycloud.domain.credential.CredentialHealth;
import com.aipaas.anycloud.domain.credential.CredentialHealthService;
import com.aipaas.anycloud.domain.credential.CspCredentialService;
import com.aipaas.anycloud.domain.credential.api.request.CreateCspCredentialRequest;
import com.aipaas.anycloud.domain.credential.api.request.UpdateCspCredentialRequest;
import com.aipaas.anycloud.domain.credential.api.response.CspCredentialResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/v1/credentials")
@Tag(name = "CSP Credentials (v1)", description = "CSP 자격증명 CRUD")
public class CspCredentialController {

    private final CspCredentialService cspCredentialService;
    private final CredentialHealthService credentialHealthService;

    @GetMapping
    @Operation(summary = "CSP 자격증명 목록 조회", description = "VM 기반 클러스터 생성에 사용할 CSP 자격증명을 조회합니다.")
    @ApiResponses({@ApiResponse(responseCode = "200", description = "CSP 자격증명 목록 조회 성공")})
    public ResponseEntity<ApiSuccessResponse<PagedData<CspCredentialResponse>>> getCredentials() {
        return new ResponseEntity<>(
                ApiSuccessResponse.of(
                        HttpStatus.OK.value(),
                        "VM credentials loaded",
                        PagedData.of(cspCredentialService.getCredentials())),
                new HttpHeaders(),
                HttpStatus.OK);
    }

    @GetMapping("/{credentialId}")
    @Operation(summary = "CSP 자격증명 상세 조회", description = "개별 CSP 자격증명 메타데이터를 조회합니다.")
    @ApiResponses({@ApiResponse(responseCode = "200", description = "CSP 자격증명 상세 조회 성공")})
    public ResponseEntity<ApiSuccessResponse<CspCredentialResponse>> getCredential(
            @Parameter(description = "자격증명 ID", example = "cred-001") @PathVariable String credentialId) {
        return new ResponseEntity<>(
                ApiSuccessResponse.of(
                        HttpStatus.OK.value(),
                        "VM credential loaded",
                        cspCredentialService.getCredential(credentialId)),
                new HttpHeaders(),
                HttpStatus.OK);
    }

    @GetMapping("/{credentialId}/reveal")
    @Operation(
            summary = "자격증명 값 조회",
            description =
                    "등록된 값을 그대로 반환합니다. 목록, 상세 응답에는 값이 섞이지 않으며 " + "이 호출만 값을 노출합니다. 누가 언제 어느 자격증명을 봤는지 감사 로그에 남습니다.")
    @ApiResponses({@ApiResponse(responseCode = "200", description = "조회 성공")})
    public ResponseEntity<ApiSuccessResponse<Map<String, String>>> revealCredential(
            @Parameter(description = "자격증명 ID", example = "cred-001") @PathVariable String credentialId) {
        return new ResponseEntity<>(
                ApiSuccessResponse.of(
                        HttpStatus.OK.value(),
                        "Credential revealed",
                        cspCredentialService.revealCredential(credentialId)),
                new HttpHeaders(),
                HttpStatus.OK);
    }

    @PostMapping("/{credentialId}/health")
    @Operation(
            summary = "자격증명 가용성 확인",
            description = "CSP API 를 실제로 호출해 자격증명이 쓸 수 있는 상태인지 확인합니다. " + "비밀값은 내보내지 않습니다. 실패 시 원인 분류와 할 일을 함께 반환합니다.")
    @ApiResponses({@ApiResponse(responseCode = "200", description = "확인 완료 (healthy=false 도 200)")})
    public ResponseEntity<ApiSuccessResponse<CredentialHealth>> checkHealth(
            @Parameter(description = "자격증명 ID", example = "cred-001") @PathVariable String credentialId) {
        CspCredentialResponse credential = cspCredentialService.getCredential(credentialId);
        // 확인 자체는 성공한 호출이다. 결과가 실패라고 HTTP 오류로 내보내면 화면이 배지 대신
        // 오류 처리를 하게 된다.
        return new ResponseEntity<>(
                ApiSuccessResponse.of(
                        HttpStatus.OK.value(),
                        "Credential health checked",
                        credentialHealthService.check(credential.getProvider(), credentialId)),
                new HttpHeaders(),
                HttpStatus.OK);
    }

    @PostMapping("/{credentialId}/health/refresh")
    @Operation(
            summary = "자격증명 가용성 갱신 (오래된 것만)",
            description = "마지막 확인이 오래됐을 때만 CSP API 를 호출합니다. 최근에 확인했으면 " + "저장된 결과를 그대로 반환합니다. 화면 진입 시 자동 갱신용입니다.")
    @ApiResponses({@ApiResponse(responseCode = "200", description = "갱신 완료 또는 저장값 반환")})
    public ResponseEntity<ApiSuccessResponse<CredentialHealth>> refreshHealth(
            @Parameter(description = "자격증명 ID", example = "cred-001") @PathVariable String credentialId) {
        CspCredentialResponse credential = cspCredentialService.getCredential(credentialId);
        return new ResponseEntity<>(
                ApiSuccessResponse.of(
                        HttpStatus.OK.value(),
                        "Credential health refreshed",
                        credentialHealthService.refreshIfStale(credential.getProvider(), credentialId)),
                new HttpHeaders(),
                HttpStatus.OK);
    }

    @PostMapping
    @Operation(summary = "CSP 자격증명 생성", description = "수동 저장형 또는 환경변수 기반 CSP 자격증명을 등록합니다.")
    @ApiResponses({@ApiResponse(responseCode = "201", description = "CSP 자격증명 생성 성공")})
    public ResponseEntity<ApiSuccessResponse<CspCredentialResponse>> createCredential(
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                            required = true,
                            content =
                                    @Content(
                                            mediaType = "application/json",
                                            schema = @Schema(implementation = CreateCspCredentialRequest.class),
                                            examples = {
                                                @ExampleObject(
                                                        name = "AWS MANUAL",
                                                        value =
                                                                """
											{
											  "credentialId": "cred-aws-001",
											  "credentialName": "prod aws account",
											  "provider": "AWS",
											  "secrets": {
											    "AWS_ACCESS_KEY_ID": "AKIA...",
											    "AWS_SECRET_ACCESS_KEY": "..."
											  }
											}"""),
                                                @ExampleObject(
                                                        name = "GCP MANUAL (JSON key)",
                                                        value =
                                                                """
											{
											  "credentialId": "cred-gcp-001",
											  "credentialName": "prod gcp project",
											  "provider": "GCP",
											  "secrets": {
											    "GOOGLE_APPLICATION_CREDENTIALS_JSON": "{\\"type\\":\\"service_account\\",...}",
											    "GCP_PROJECT_ID": "my-project"
											  }
											}""")
                                            }))
                    @Valid
                    @RequestBody
                    CreateCspCredentialRequest request) {
        return new ResponseEntity<>(
                ApiSuccessResponse.of(
                        HttpStatus.CREATED.value(),
                        "VM credential created",
                        cspCredentialService.createCredential(request)),
                new HttpHeaders(),
                HttpStatus.CREATED);
    }

    @PatchMapping("/{credentialId}")
    @Operation(
            summary = "CSP 자격증명 수정",
            description = "설명과 값을 바꿉니다. 이름과 프로바이더는 바꿀 수 없습니다 — "
                    + "vm_cluster 가 프로비저닝 당시 이름을 기록으로 들고 있고, 프로바이더가 바뀌면 키 구성이 달라집니다. "
                    + "값은 통째로 교체됩니다. 일부만 보내면 나머지는 사라집니다.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "수정 성공"),
        @ApiResponse(responseCode = "400", description = "빈 값 또는 프로바이더가 요구하는 키 누락"),
        @ApiResponse(responseCode = "404", description = "자격증명 없음")
    })
    public ResponseEntity<ApiSuccessResponse<CspCredentialResponse>> updateCredential(
            @Parameter(description = "자격증명 ID", example = "cred-001") @PathVariable String credentialId,
            @RequestBody UpdateCspCredentialRequest request) {
        return ResponseEntity.ok(ApiSuccessResponse.of(
                HttpStatus.OK.value(),
                "VM credential updated",
                cspCredentialService.updateCredential(credentialId, request)));
    }

    @DeleteMapping("/{credentialId}")
    @Operation(summary = "CSP 자격증명 삭제", description = "저장된 CSP 자격증명을 삭제합니다.")
    @ApiResponses({@ApiResponse(responseCode = "200", description = "CSP 자격증명 삭제 성공")})
    public ResponseEntity<ApiSuccessResponse<ActionResponse>> deleteCredential(
            @Parameter(description = "자격증명 ID", example = "cred-001") @PathVariable String credentialId) {
        cspCredentialService.deleteCredential(credentialId);
        return new ResponseEntity<>(
                ApiSuccessResponse.of(
                        HttpStatus.OK.value(),
                        "VM credential deleted",
                        ActionResponse.builder()
                                .resourceType("vmCredential")
                                .resourceId(credentialId)
                                .operation("delete")
                                .state(HttpStatus.OK.name())
                                .build()),
                new HttpHeaders(),
                HttpStatus.OK);
    }
}
