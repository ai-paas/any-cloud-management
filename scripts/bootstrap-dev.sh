#!/usr/bin/env bash
#
# bootstrap-dev.sh — 신규 개발자 첫 setup 자동화.
#
# 동작:
#   1. .env 없으면 .env.sample 복사 후 빈 값을 `openssl rand -hex N` 으로 자동 채움.
#   2. docker / openssl 등 prerequisite 검사.
#   3. (선택) make dev-up 호출 — 인자 'no-up' 주면 스킵.
#   4. backend healthcheck — http://localhost:8888/actuator/health 200 까지 60s 대기.
#
# 멱등 — 이미 .env 있으면 손대지 않음. dev stack 이 이미 떠 있으면 healthcheck 만.
#
# 사용:
#   ./scripts/bootstrap-dev.sh           # 전체 (.env + stack up + healthcheck)
#   ./scripts/bootstrap-dev.sh no-up     # .env 만 생성, stack 은 사용자가 따로
#
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT_DIR"

RED='\033[0;31m'; GREEN='\033[0;32m'; YELLOW='\033[0;33m'; NC='\033[0m'
info() { printf "${GREEN}[bootstrap]${NC} %s\n" "$*"; }
warn() { printf "${YELLOW}[bootstrap]${NC} %s\n" "$*"; }
err() { printf "${RED}[bootstrap]${NC} %s\n" "$*" >&2; }

# ──────────────────────────────────────────────────────────────────────────────
# 1. Prerequisite 검사
# ──────────────────────────────────────────────────────────────────────────────
missing=0
for cmd in docker openssl; do
  if ! command -v "$cmd" >/dev/null 2>&1; then
    err "$cmd 미설치 — 설치 후 재시도."
    missing=1
  fi
done
if ! docker compose version >/dev/null 2>&1; then
  err "docker compose v2 미설치 (docker-compose v1 은 미지원)."
  missing=1
fi
[ "$missing" = "0" ] || exit 1

# ──────────────────────────────────────────────────────────────────────────────
# 2. .env 자동 생성
# ──────────────────────────────────────────────────────────────────────────────
if [ -f .env ]; then
  info ".env 이미 존재 — 그대로 사용 (idempotent)."
else
  if [ ! -f .env.sample ]; then
    err ".env.sample 누락 — repo 손상?"
    exit 1
  fi
  info ".env.sample → .env 복사 후 빈 secret 을 openssl rand 로 자동 채움."
  cp .env.sample .env

  # KEY= (빈 값) 줄을 찾아 openssl rand -hex 32 로 채움. sentinel comment 보존.
  # macOS / linux 호환을 위해 sed -i 대신 tmp file + mv 사용.
  tmp=$(mktemp)
  while IFS= read -r line; do
    if [[ "$line" =~ ^([A-Z_]+)=$ ]]; then
      key="${BASH_REMATCH[1]}"
      val=$(openssl rand -hex 32)
      printf "%s=%s\n" "$key" "$val" >>"$tmp"
    else
      printf "%s\n" "$line" >>"$tmp"
    fi
  done <.env
  mv "$tmp" .env
  info ".env 생성 완료 (random hex32 secret 자동 주입)."
  warn "운영 stack 으로 옮길 땐 .env 의 secret 을 별도로 rotate 하세요."
fi

# ──────────────────────────────────────────────────────────────────────────────
# 3. Stack 기동 (선택)
# ──────────────────────────────────────────────────────────────────────────────
mode="${1:-up}"
if [ "$mode" = "no-up" ]; then
  info "no-up 모드 — .env 만 생성하고 종료."
  exit 0
fi

info "make dev-up — docker-compose.dev.yml 의 full stack 기동 (cold ~3-5m)."
if ! make dev-up; then
  err "make dev-up 실패 — docker 로그를 확인하세요: make dev-logs"
  exit 1
fi

# ──────────────────────────────────────────────────────────────────────────────
# 4. Healthcheck
# ──────────────────────────────────────────────────────────────────────────────
info "Backend healthcheck — http://localhost:8888/actuator/health (최대 60s 대기)"
deadline=$(( $(date +%s) + 60 ))
while [ "$(date +%s)" -lt "$deadline" ]; do
  if curl -sf -m 3 http://localhost:8888/actuator/health -o /dev/null; then
    info "Backend up. Swagger: http://localhost:8888/docs"
    info "다음 단계: bru run .bruno/  또는  open http://localhost:8888/test-console/index.html"
    exit 0
  fi
  sleep 2
done

err "Backend 가 60s 안에 startup 못 함. 로그 확인: make dev-logs"
exit 1
