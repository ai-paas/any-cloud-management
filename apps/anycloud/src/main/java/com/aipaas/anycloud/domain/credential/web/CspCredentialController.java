package com.aipaas.anycloud.domain.credential.web;

import com.aipaas.anycloud.common.web.ActionResponse;
import com.aipaas.anycloud.common.web.ApiSuccessResponse;
import com.aipaas.anycloud.common.web.PagedData;
import com.aipaas.anycloud.domain.credential.CspCredentialService;
import com.aipaas.anycloud.domain.credential.api.request.CreateCspCredentialRequest;
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
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
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
