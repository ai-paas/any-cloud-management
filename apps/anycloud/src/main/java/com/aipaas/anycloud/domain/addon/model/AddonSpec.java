package com.aipaas.anycloud.domain.addon.model;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Frontend 가 보내는 per-cluster addon spec — cluster 생성 시 또는 POST addon endpoint.
 *
 * <p>두 모드 — (1) catalog 기반 (Option B): {@link #catalogId} 만 지정, 나머지 catalog default 사용.
 * (2) custom: catalogId null + 나머지 모든 field 명시.
 *
 * <p>field 우선순위: catalog 의 default ⇐ AddonSpec override. null/blank override 는 catalog 값 보존.
 *
 * @param type          installer strategy dispatch key (required).
 * @param catalogId     addons.yaml 의 id (optional, custom 일 때 null).
 * @param releaseName   helm release name. blank 이면 catalog default 또는 chartName.
 * @param namespace     설치 namespace. blank 이면 catalog default.
 * @param chartRepo     repo alias (catalog 가 정의했으면 override).
 * @param chartName     chart name (custom 모드에서 필수).
 * @param chartVersion  chart version (catalog override 가능).
 * @param repoUrl       명시 URL. null 이면 backend 가 HelmRepoEntity lookup.
 * @param valuesYaml    사용자 values override (JSON 또는 YAML string).
 * @param enabled       false 면 row 는 만들되 auto-install 제외 (수동 toggle 용).
 */
@Schema(name = "AddonSpec", description = "Per-cluster addon install spec")
public record AddonSpec(
        @NotNull @Schema(description = "Addon strategy type", example = "MONITORING") AddonType type,
        @Size(max = 64)
                @Pattern(regexp = "^[a-z0-9][a-z0-9-]*$", message = "catalogId must be lowercase-kebab")
                @Schema(description = "Catalog ref (Option B). null = custom spec.", example = "kube-prometheus-stack")
                String catalogId,
        @Size(max = 128) @Schema(description = "Helm release name override", example = "kube-prometheus-stack")
                String releaseName,
        @Size(max = 128) @Schema(description = "Target namespace override", example = "monitoring") String namespace,
        @Size(max = 128) @Schema(description = "Repo alias override", example = "prometheus-community")
                String chartRepo,
        @Size(max = 128)
                @Schema(description = "Chart name (required if catalogId null)", example = "kube-prometheus-stack")
                String chartName,
        @Size(max = 32) @Schema(description = "Chart version override", example = "65.0.0") String chartVersion,
        @Size(max = 512)
                @Schema(
                        description = "M8 — explicit repo URL (alias resolve 우회)",
                        example = "https://prometheus-community.github.io/helm-charts")
                String repoUrl,
        @Schema(description = "Values JSON/YAML override") String valuesYaml,
        @Schema(description = "false = soft disable (auto-install 제외)", defaultValue = "true") Boolean enabled) {}
