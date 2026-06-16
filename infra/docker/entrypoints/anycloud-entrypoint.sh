#!/bin/sh
# anycloud Pulumi 컨테이너 entrypoint. 다음 책임:
#   1. 외부 named volume 으로 mount 되는 Pulumi state / cache 디렉토리의 ownership 보정.
#      Docker named volume 은 mount 시 root:root 로 만들어지는 경우가 있어 non-root user
#      (anycloud) 가 쓰기 실패하는 사례 차단.
#   2. APP_ROLE=worker 면 Spring profile 자동 전환 (web 비활성).
#   3. PULUMI_PASSPHRASE 가 sentinel ("change-me") 이면 부팅 차단.
#   4. JAVA_OPTS / SPRING_ARGS 를 그대로 전달.
set -eu

# Sanity: critical secrets sentinel reject.
case "${PULUMI_PASSPHRASE:-}" in
  ""|"change-me"|"changeme"|"anycloud"|"secret")
    echo "FATAL: PULUMI_PASSPHRASE is unset or a weak sentinel. Set a strong random value." >&2
    exit 1
    ;;
esac

# Volume ownership 보정. anycloud user 가 작성 가능하도록 chown 시도.
# 권한이 없으면 (rootless docker 등) silently skip — 정상 동작하면 OK.
ensure_writable() {
  for d in "$@"; do
    [ -d "$d" ] || mkdir -p "$d" 2>/dev/null || true
    if [ ! -w "$d" ]; then
      # USER anycloud 컨텍스트에서는 chown 권한 없음 — 부팅 시 init container 또는
      # tmpfs 권장. 실패 시 경고만.
      echo "WARN: $d not writable by current user ($(id -un))" >&2
    fi
  done
}
ensure_writable \
  /app/.pulumi \
  /app/runtime \
  /app/.cache

APP_ROLE="${APP_ROLE:-backend}"
SPRING_ARGS="${SPRING_ARGS:-}"

# 컨테이너 공통 — application-docker.yaml 의 DB / JPA 설정 활성화.
# 기존: Dockerfile 이 application.properties_docker 를 application.properties 로 COPY 해서
#       자동 활성. 이제 profile 명시 필요.
# worker role 은 docker + vm-cluster-worker 두 profile layer.
if [ "$APP_ROLE" = "worker" ]; then
  export SPRING_PROFILES_ACTIVE="${SPRING_PROFILES_ACTIVE:-docker,vm-cluster-worker}"
  export SPRING_MAIN_WEB_APPLICATION_TYPE="${SPRING_MAIN_WEB_APPLICATION_TYPE:-none}"
else
  export SPRING_PROFILES_ACTIVE="${SPRING_PROFILES_ACTIVE:-docker}"
fi

# shellcheck disable=SC2086  # JAVA_OPTS / SPRING_ARGS intentional word-split.
exec java ${JAVA_OPTS} -jar /app/app.jar ${SPRING_ARGS} "$@"
