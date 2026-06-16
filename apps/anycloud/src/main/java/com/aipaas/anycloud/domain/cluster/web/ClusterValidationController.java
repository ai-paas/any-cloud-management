package com.aipaas.anycloud.domain.cluster.web;

import com.aipaas.anycloud.common.web.ApiSuccessResponse;
import com.aipaas.anycloud.domain.provisioning.VmClusterService;
import com.aipaas.anycloud.domain.provisioning.api.request.ProvisionClusterRequest;
import com.aipaas.anycloud.domain.provisioning.api.response.VmClusterPreflightResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 클러스터 생성 사전 검증. 결과 자체가 자원이므로 POST 로 생성하고 결과를 본문에 반환.
 * preflight 동사 path 대체.
 * <pre>
 * POST /v1/cluster-validations
 * body: ProvisionClusterRequest (VM 생성과 동일 schema)
 * → 201 + 검증 결과 + 추후 create 호출용 hint
 * </pre>
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/v1/cluster-validations")
@Tag(name = "Cluster Validations (v1)", description = "Cluster 생성 사전 검증 (preflight)")
public class ClusterValidationController {

    private final VmClusterService vmClusterService;

    @PostMapping
    @Operation(
            summary = "VM cluster 생성 사전 검증",
            description = "검증 결과 자체가 자원. body 는 /v1/clusters POST 의 vm-source spec 과 동일.")
    public ResponseEntity<ApiSuccessResponse<VmClusterPreflightResponse>> validate(
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                            required = true,
                            content =
                                    @Content(
                                            mediaType = "application/json",
                                            schema = @Schema(implementation = ProvisionClusterRequest.class),
                                            examples = {
                                                @ExampleObject(
                                                        name = "AWS preflight",
                                                        value =
                                                                """
											{
											  "clusterName": "demo-aws-01",
											  "provider": "aws",
											  "region": "ap-northeast-2",
											  "environment": "dev",
											  "credentialId": "cred-aws-001",
											  "config": {
											    "workerCount": "3",
											    "instanceType": "t3.medium"
											  }
											}"""),
                                                @ExampleObject(
                                                        name = "OpenStack preflight",
                                                        value =
                                                                """
											{
											  "clusterName": "demo-os-01",
											  "provider": "openstack",
											  "region": "RegionOne",
											  "environment": "dev",
											  "credentialId": "cred-os-001",
											  "config": {
											    "workerCount": "2",
											    "flavorName": "m1.medium",
											    "imageName": "ubuntu-22.04"
											  }
											}""")
                                            }))
                    @Valid
                    @RequestBody
                    ProvisionClusterRequest body) {
        VmClusterPreflightResponse result = vmClusterService.preflightVmCluster(body);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiSuccessResponse.of(HttpStatus.CREATED.value(), "Cluster validation completed", result)
                        .withLinks(Map.of("createCluster", "/v1/clusters")));
    }

    @PostMapping("/preview")
    @Operation(
            summary = "VM cluster 생성 미리보기 (pulumi preview)",
            description = "실제로 생성될 CSP resource 계획을 반환 — `pulumi preview --json` 기반. "
                    + "CSP 자원은 만들지 않는다. preflight (정적 검증, ~ms) 와 달리 Pulumi CLI + CSP API 를 "
                    + "실제 호출하므로 수십 초 걸릴 수 있다 — client timeout 60s 이상 권장. "
                    + "신규 cluster 는 preview 용 stack 을 임시 생성 후 정리, 기존 stack 은 diff 만 반환.")
    public ResponseEntity<
                    ApiSuccessResponse<com.aipaas.anycloud.domain.provisioning.api.response.VmClusterPreviewResponse>>
            preview(@Valid @RequestBody ProvisionClusterRequest body) {
        var result = vmClusterService.previewVmCluster(body);
        return ResponseEntity.ok(ApiSuccessResponse.of(HttpStatus.OK.value(), "Cluster preview completed", result)
                .withLinks(Map.of("createCluster", "/v1/clusters")));
    }
}
