package com.aipaas.anycloud.domain.provisioning.web;

import com.aipaas.anycloud.common.web.ApiSuccessResponse;
import com.aipaas.anycloud.common.web.OffsetPage;
import com.aipaas.anycloud.common.web.PagedData;
import com.aipaas.anycloud.domain.provisioning.api.response.VmNodeListItemResponse;
import com.aipaas.anycloud.domain.provisioning.query.VmClusterQueryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 노드 단위 조회.
 *
 * <p>{@code /v1/vms} 아래 두면 클러스터 이름 path 변수와 겹친다. 별도 namespace 로 뺀다.
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/v1/nodes")
@Tag(name = "Nodes (v1)", description = "VM 클러스터를 구성하는 개별 노드")
public class VmNodeController {

    private final VmClusterQueryService vmClusterQueryService;

    @GetMapping
    @Operation(summary = "노드 목록", description = "클러스터 경계를 넘어 노드를 한 행씩 조회. 삭제된 클러스터는 제외.")
    public ResponseEntity<ApiSuccessResponse<PagedData<VmNodeListItemResponse>>> list(
            @RequestParam(required = false) String provider,
            @RequestParam(required = false) String clusterName,
            @Parameter(description = "0-based 페이지. 비우면 전부") @RequestParam(required = false) Integer page,
            @Parameter(description = "페이지 크기. 비우면 전부") @RequestParam(required = false) Integer size) {
        /*
         * 노드는 클러스터 행의 stack output 에서 풀어낸 파생 목록이라 DB 에서 자를 수 없다.
         * 자르는 곳을 여기 한 곳으로 두어 중간 계층이 다시 자르지 않게 한다.
         */
        OffsetPage<VmNodeListItemResponse> paged =
                OffsetPage.of(vmClusterQueryService.listNodes(provider, clusterName), page, size);
        return ResponseEntity.ok(
                ApiSuccessResponse.of(HttpStatus.OK.value(), "Nodes loaded", PagedData.of(paged.items()))
                        .withPagedMeta(paged.items().size(), null, paged.total()));
    }
}
