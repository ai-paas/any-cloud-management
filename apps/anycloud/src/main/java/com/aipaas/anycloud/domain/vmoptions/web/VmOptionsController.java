package com.aipaas.anycloud.domain.vmoptions.web;

import com.aipaas.anycloud.common.web.ApiSuccessResponse;
import com.aipaas.anycloud.common.web.PagedData;
import com.aipaas.anycloud.domain.credential.CredentialSchema;
import com.aipaas.anycloud.domain.credential.api.CredentialFieldSchema;
import com.aipaas.anycloud.domain.provisioning.model.SupportedProvisioningProvider;
import com.aipaas.anycloud.domain.vmoptions.ProviderConfigSchemaService;
import com.aipaas.anycloud.domain.vmoptions.VmOptionsService;
import com.aipaas.anycloud.domain.vmoptions.api.ProviderConfigKey;
import com.aipaas.anycloud.domain.vmoptions.api.ProvisioningDefaults;
import com.aipaas.anycloud.domain.vmoptions.api.SpecFilter;
import com.aipaas.anycloud.domain.vmoptions.api.VmOptionImage;
import com.aipaas.anycloud.domain.vmoptions.api.VmOptionProvider;
import com.aipaas.anycloud.domain.vmoptions.api.VmOptionRegion;
import com.aipaas.anycloud.domain.vmoptions.api.VmOptionSpec;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/v1/providers")
@Tag(name = "Providers (v1)", description = "VM provisioning 카탈로그 — providers / regions / specs / images")
public class VmOptionsController {

    private final VmOptionsService vmOptionsService;
    private final ProviderConfigSchemaService providerConfigSchemaService;
    private final com.aipaas.anycloud.domain.vmoptions.ProvisioningDefaultsService provisioningDefaultsService;

    @GetMapping
    @Operation(summary = "지원 CSP 목록 조회", description = "VM 기반 클러스터 생성에 사용할 CSP 목록과 구현 상태를 조회합니다.")
    @ApiResponses({@ApiResponse(responseCode = "200", description = "Provider 목록 조회 성공")})
    public ResponseEntity<ApiSuccessResponse<PagedData<VmOptionProvider>>> listProviders() {
        return new ResponseEntity<>(
                ApiSuccessResponse.of(
                        HttpStatus.OK.value(), "VM providers loaded", PagedData.of(vmOptionsService.getProviders())),
                new HttpHeaders(),
                HttpStatus.OK);
    }

    @GetMapping("/{provider}/regions")
    @Operation(summary = "리전 조회", description = "선택한 CSP에서 사용 가능한 리전을 조회합니다.")
    @ApiResponses({@ApiResponse(responseCode = "200", description = "리전 목록 조회 성공")})
    public ResponseEntity<ApiSuccessResponse<PagedData<VmOptionRegion>>> listRegions(
            @Parameter(description = "클라우드 제공자", example = "AWS") @PathVariable String provider,
            @Parameter(
                            description = "사용자가 등록한 credential ID (MANUAL sourceType) — 미지정 시 환경변수 fallback",
                            example = "cred-uuid")
                    @RequestParam(required = false)
                    String credentialId) {
        return new ResponseEntity<>(
                ApiSuccessResponse.of(
                        HttpStatus.OK.value(),
                        "VM regions loaded",
                        PagedData.of(vmOptionsService.getRegions(provider, credentialId))),
                new HttpHeaders(),
                HttpStatus.OK);
    }

    @GetMapping("/{provider}/specs")
    @Operation(summary = "VM 스펙 조회", description = "선택한 CSP와 리전에서 사용 가능한 VM 스펙(flavor, instance type)을 조회합니다.")
    @ApiResponses({@ApiResponse(responseCode = "200", description = "VM 스펙 목록 조회 성공")})
    public ResponseEntity<ApiSuccessResponse<PagedData<VmOptionSpec>>> listSpecs(
            @Parameter(description = "클라우드 제공자", example = "AWS") @PathVariable String provider,
            @Parameter(description = "사용자가 등록한 credential ID — 미지정 시 환경변수 fallback", example = "cred-uuid")
                    @RequestParam(required = false)
                    String credentialId,
            @Parameter(description = "리전", example = "ap-northeast-2") @RequestParam(required = false) String region,
            @Parameter(description = "검색 키워드", example = "standard") @RequestParam(required = false) String keyword,
            @Parameter(description = "GPU 전용 스펙만 조회", example = "false")
                    @RequestParam(required = false, defaultValue = "false")
                    Boolean gpuOnly,
            @Parameter(description = "최대 조회 개수", example = "20") @RequestParam(required = false) Integer limit) {
        return new ResponseEntity<>(
                ApiSuccessResponse.of(
                        HttpStatus.OK.value(),
                        "VM specs loaded",
                        PagedData.of(
                                vmOptionsService.getSpecs(provider, credentialId, region, keyword, gpuOnly, limit))),
                new HttpHeaders(),
                HttpStatus.OK);
    }

    @GetMapping("/{provider}/credential-schema")
    @Operation(
            summary = "자격증명 입력 필드 schema",
            description = "프로바이더별로 자격증명에 무엇을 받아야 하는지 — key, label, required, secret, "
                    + "multiline, group 포함. 화면이 KEY=VALUE 를 직접 받지 않도록 입력 폼을 만드는 데 쓴다.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Schema 조회 성공"),
        @ApiResponse(responseCode = "400", description = "지원하지 않는 provider")
    })
    public ResponseEntity<ApiSuccessResponse<PagedData<CredentialFieldSchema>>> getCredentialSchema(
            @Parameter(description = "클라우드 제공자", example = "AWS") @PathVariable String provider) {
        List<CredentialFieldSchema> schema = CredentialSchema.of(SupportedProvisioningProvider.from(provider));
        return new ResponseEntity<>(
                ApiSuccessResponse.of(HttpStatus.OK.value(), "Credential schema loaded", PagedData.of(schema)),
                new HttpHeaders(),
                HttpStatus.OK);
    }

    @GetMapping("/provisioning-defaults")
    @Operation(
            summary = "CSP 별 생성 기본값",
            description =
                    "계정에서 실제로 고를 수 있는 값을 조회해 CSP 마다 지금 통과하는 요청 한 벌을 조립한다. " + "만들 수 없는 CSP 는 ready=false 와 이유를 함께 준다.")
    @ApiResponses({@ApiResponse(responseCode = "200", description = "기본값 조회 성공")})
    public ResponseEntity<ApiSuccessResponse<PagedData<ProvisioningDefaults>>> getProvisioningDefaults(
            @Parameter(description = "한 CSP 만 조회. 비우면 전부", example = "AWS") @RequestParam(required = false)
                    String provider,
            @Parameter(description = "최소 vCPU. 비우면 2") @RequestParam(required = false) Integer minVcpu,
            @Parameter(description = "최소 메모리 GB. 비우면 4") @RequestParam(required = false) Double minMemoryGb,
            @Parameter(description = "GPU 인스턴스로 고를지. 비우면 제외") @RequestParam(required = false) Boolean gpu) {
        return new ResponseEntity<>(
                ApiSuccessResponse.of(
                        HttpStatus.OK.value(),
                        "Provisioning defaults loaded",
                        PagedData.of(provisioningDefaultsService.listDefaults(
                                provider, SpecFilter.of(minVcpu, minMemoryGb, gpu)))),
                new HttpHeaders(),
                HttpStatus.OK);
    }

    @GetMapping("/{provider}/config-schema")
    @Operation(
            summary = "Provisioning config 키 schema",
            description = "spec.config 에 사용할 수 있는 모든 'anycloud-k8s:*' 키 목록 — type, required, default, "
                    + "description, allowedValues 포함. 클라이언트 form generation / validation 미리보기에 사용.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Schema 조회 성공"),
        @ApiResponse(responseCode = "400", description = "지원하지 않는 provider")
    })
    public ResponseEntity<ApiSuccessResponse<PagedData<ProviderConfigKey>>> getConfigSchema(
            @Parameter(description = "클라우드 제공자", example = "AWS") @PathVariable String provider,
            @Parameter(
                            description = "사용자가 등록한 credential ID — 주면 계정에서 고를 수 있는 값을 allowedValues 에 채운다",
                            example = "cred-uuid")
                    @RequestParam(required = false)
                    String credentialId,
            @Parameter(description = "리전 — 리전마다 고를 수 있는 값이 다른 키에 필요", example = "jp-tok")
                    @RequestParam(required = false)
                    String region) {
        List<ProviderConfigKey> schema = vmOptionsService.getConfigSchema(provider, credentialId, region);
        return new ResponseEntity<>(
                ApiSuccessResponse.of(HttpStatus.OK.value(), "Config schema loaded", PagedData.of(schema)),
                new HttpHeaders(),
                HttpStatus.OK);
    }

    @GetMapping("/{provider}/images")
    @Operation(summary = "OS 이미지 조회", description = "선택한 CSP에서 배포 가능한 운영체제 이미지를 조회합니다.")
    @ApiResponses({@ApiResponse(responseCode = "200", description = "OS 이미지 목록 조회 성공")})
    public ResponseEntity<ApiSuccessResponse<PagedData<VmOptionImage>>> listImages(
            @Parameter(description = "클라우드 제공자", example = "AWS") @PathVariable String provider,
            @Parameter(description = "사용자가 등록한 credential ID — 미지정 시 환경변수 fallback", example = "cred-uuid")
                    @RequestParam(required = false)
                    String credentialId,
            @Parameter(description = "리전", example = "ap-northeast-2") @RequestParam(required = false) String region,
            @Parameter(description = "검색 키워드", example = "ubuntu") @RequestParam(required = false) String keyword,
            @Parameter(description = "아키텍처", example = "x86_64") @RequestParam(required = false) String architecture,
            @Parameter(description = "이미지 owner 또는 publisher", example = "canonical") @RequestParam(required = false)
                    String owner,
            @Parameter(description = "최대 조회 개수", example = "20") @RequestParam(required = false) Integer limit) {
        return new ResponseEntity<>(
                ApiSuccessResponse.of(
                        HttpStatus.OK.value(),
                        "VM images loaded",
                        PagedData.of(vmOptionsService.getImages(
                                provider, credentialId, region, keyword, architecture, owner, limit))),
                new HttpHeaders(),
                HttpStatus.OK);
    }
}
