#!/usr/bin/env bash
#
# 새 CSP provider 추가 시 touch point 안내 + 스캐폴드 생성.
#
# 사용:
#   ./scripts/new-provider.sh <csp-name>
#   예: ./scripts/new-provider.sh hetzner
#
# 본 script 는 실 file 을 부분만 자동 생성 — Java provider class, pricing yaml stub, README
# 갱신 안내. Go Pulumi side / config enum / docs 는 수동 검토 필요 항목으로 출력만.

set -euo pipefail

if [ $# -lt 1 ]; then
  echo "Usage: $0 <csp-name>"
  echo "  예: $0 hetzner"
  exit 1
fi

CSP_LOWER="$1"
CSP_PASCAL="$(echo "${CSP_LOWER:0:1}" | tr '[:lower:]' '[:upper:]')${CSP_LOWER:1}"
CSP_UPPER="$(echo "$CSP_LOWER" | tr '[:lower:]' '[:upper:]')"

REPO_ROOT="$(cd "$(dirname "$0")/.." && pwd)"

JAVA_PROVIDER="$REPO_ROOT/apps/anycloud/src/main/java/com/aipaas/anycloud/domain/vmoptions/providers/${CSP_PASCAL}VmOptionsProvider.java"
GO_FACTORY="$REPO_ROOT/infra/pulumi/pkg/providers/factory_${CSP_LOWER}.go"
GO_PKG_DIR="$REPO_ROOT/infra/pulumi/pkg/providers/${CSP_LOWER}"
PRICING_YAML="$REPO_ROOT/apps/anycloud/src/main/resources/pricing/${CSP_LOWER}.yaml"

echo "===> 새 CSP provider 추가: $CSP_PASCAL ($CSP_LOWER)"
echo ""

# ---------------- 1. Java provider class ----------------
echo "[1/6] Java VmOptionsProvider scaffold"
if [ -e "$JAVA_PROVIDER" ]; then
  echo "  ⚠ 이미 존재: $JAVA_PROVIDER (skip)"
else
  cat > "$JAVA_PROVIDER" <<EOF
package com.aipaas.anycloud.domain.vmoptions.providers;

import com.aipaas.anycloud.domain.vmoptions.VmOptionImage;
import com.aipaas.anycloud.domain.vmoptions.VmOptionRegion;
import com.aipaas.anycloud.domain.vmoptions.VmOptionSpec;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * ${CSP_PASCAL} CSP provider — region / spec / image catalog.
 *
 * <p>TODO: ${CSP_PASCAL} SDK 호출 또는 정적 catalog 추가. AbstractVmOptionsProvider 의 fallback
 * (circuit breaker + 빈 list) 활용.
 */
@Slf4j
@Component
public class ${CSP_PASCAL}VmOptionsProvider extends AbstractVmOptionsProvider {

    @Override
    public String supportedProvider() {
        return "${CSP_LOWER}";
    }

    @Override
    public List<VmOptionRegion> listRegions(String credentialId) {
        // TODO: ${CSP_PASCAL} SDK 호출 또는 정적 region catalog
        return List.of();
    }

    @Override
    public List<VmOptionSpec> listSpecs(String credentialId, String region, String keyword, Boolean gpuOnly, int limit) {
        // TODO: ${CSP_PASCAL} SDK 호출
        return List.of();
    }

    @Override
    public List<VmOptionImage> listImages(
            String credentialId, String region, String keyword, String architecture, String owner, int limit) {
        // TODO: ${CSP_PASCAL} SDK 호출
        return List.of();
    }
}
EOF
  echo "  ✓ 생성: $JAVA_PROVIDER"
fi
echo ""

# ---------------- 2. Pricing YAML ----------------
echo "[2/6] Pricing catalog (정적)"
if [ -e "$PRICING_YAML" ]; then
  echo "  ⚠ 이미 존재: $PRICING_YAML (skip)"
else
  cat > "$PRICING_YAML" <<EOF
# ${CSP_PASCAL} on-demand pricing (USD/hour).
# 출처: 공식 가격 페이지 (수기 입력). instance type 변동 시 수동 갱신.
# 4-tier coarse approximation 으로 시작 — 실제 API 연동은 가치 검증 후.

provider: ${CSP_LOWER}
instances: []
  # TODO: 예시
  # - type: "standard-2"
  #   cpu: 2
  #   memoryGb: 4
  #   pricePerHour: 0.0250
EOF
  echo "  ✓ 생성: $PRICING_YAML"
fi
echo ""

# ---------------- 3. Go Pulumi factory ----------------
echo "[3/6] Go Pulumi factory_${CSP_LOWER}.go"
if [ -e "$GO_FACTORY" ]; then
  echo "  ⚠ 이미 존재: $GO_FACTORY (skip)"
else
  cat > "$GO_FACTORY" <<EOF
//go:build ${CSP_LOWER} || all

package providers

// TODO: ${CSP_PASCAL} provisioner factory 등록.
//
// 예시 (다른 CSP factory 참고):
//
//	import (
//	    "github.com/aipaas/anycloud/infra/pulumi/pkg/providers/${CSP_LOWER}"
//	)
//
//	func init() {
//	    Register("${CSP_LOWER}", ${CSP_LOWER}.NewProvisioner)
//	}
EOF
  echo "  ✓ 생성: $GO_FACTORY"
fi

if [ ! -d "$GO_PKG_DIR" ]; then
  mkdir -p "$GO_PKG_DIR"
  cat > "$GO_PKG_DIR/${CSP_LOWER}.go" <<EOF
// Package ${CSP_LOWER} — Pulumi provisioner for ${CSP_PASCAL}.
//
// TODO: VPC / Subnet / SG / Instance / SSH key 생성 로직.
// 참고: infra/pulumi/pkg/providers/aws/aws.go
package ${CSP_LOWER}
EOF
  echo "  ✓ 생성: $GO_PKG_DIR/${CSP_LOWER}.go (stub)"
else
  echo "  ⚠ 폴더 이미 존재: $GO_PKG_DIR (skip)"
fi
echo ""

# ---------------- 4. 수동 검토 항목 안내 ----------------
echo "[4/6] 다음은 수동 작업 필요:"
echo ""
echo "  a) SupportedProvisioningProvider enum 에 ${CSP_UPPER} 추가:"
echo "     apps/anycloud/src/main/java/com/aipaas/anycloud/domain/provisioning/model/SupportedProvisioningProvider.java"
echo ""
echo "  b) CredentialField enum (auth field 추가):"
echo "     apps/anycloud/src/main/java/com/aipaas/anycloud/domain/credential/model/"
echo ""
echo "  c) CspCredentialPulumiConfigMapper 의 env→stack config 변환 추가:"
echo "     libs/cluster-provisioning-spring-boot-starter/.../service/CspCredentialPulumiConfigMapper.java"
echo ""
echo "  d) GpuFlavorMapper 의 GPU_ALIAS_INSTANCE matrix 에 ${CSP_LOWER} entry (GPU 지원 시):"
echo "     apps/anycloud/src/main/java/com/aipaas/anycloud/domain/provisioning/mapper/GpuFlavorMapper.java"
echo ""
echo "  e) docs/api/provider-credential-matrix.md 에 ${CSP_PASCAL} row 추가"
echo ""
echo "  f) CI workflow 의 publish-* image build matrix (다중 CSP 빌드 시)"
echo ""

# ---------------- 5. 검증 명령 ----------------
echo "[5/6] 작업 후 검증:"
echo "  make backend-build              # Java provider compile"
echo "  cd infra/pulumi && go vet ./...  # Go pulumi build"
echo "  make backend-test                # ArchUnit + unit test"
echo ""

# ---------------- 6. 운영 검증 ----------------
echo "[6/6] 운영 검증 (실 ${CSP_PASCAL} credential 보유 시):"
echo "  POST /v1/credentials   { provider: \"${CSP_LOWER}\", ... }"
echo "  GET  /v1/providers/${CSP_LOWER}/regions"
echo "  POST /v1/cluster-validations/preview   (preflight + Pulumi preview)"
echo ""
echo "===> Scaffold 생성 완료. 위 [4][5][6] 참고하여 진행."
