#!/usr/bin/env bash
# OpenStack floating IP 로 가는 로컬 경로를 만든다.
#
# floating IP 대역(192.168.10.0/24)은 사설망이라 개발 머신에서 직접 닿지 않는다.
# bastion 으로 여는 SSH SOCKS 프록시를 도커 네트워크 안으로 끌어와, 노드 IP 와
# 같은 주소를 가진 socat 컨테이너가 그 주소로 오는 SSH 를 중계한다.
#
#   backend 컨테이너 → 192.168.10.166 (socat 컨테이너) → SOCKS :1080 → bastion → 실제 노드
#
# floating IP 는 프로비저닝마다 새로 할당되므로 노드가 뜬 뒤에 실행해야 한다.
#
# 사용법:
#   ./os-jump.sh up 192.168.10.166 192.168.10.113   # 노드 IP 마다 터널 생성
#   ./os-jump.sh attach                              # backend, worker 를 네트워크에 연결
#   ./os-jump.sh status
#   ./os-jump.sh down                                # 터널만 정리 (컨테이너는 유지)
set -euo pipefail

NETWORK="${OS_JUMP_NETWORK:-os-jump}"
SUBNET="${OS_JUMP_SUBNET:-192.168.10.0/24}"
GATEWAY="${OS_JUMP_GATEWAY:-192.168.10.1}"
SOCKS_PORT="${OS_JUMP_SOCKS_PORT:-1080}"
ATTACH_CONTAINERS="${OS_JUMP_CONTAINERS:-anycloud-backend-dev anycloud-bootstrap-worker-dev}"

die() { echo "ERROR: $*" >&2; exit 1; }

require_socks() {
    # 프록시가 없으면 터널은 뜨지만 전부 연결 거부가 된다. 원인 찾기가 오래 걸리는 실패다.
    if ! nc -z 127.0.0.1 "$SOCKS_PORT" 2>/dev/null; then
        die "SOCKS 프록시가 127.0.0.1:${SOCKS_PORT} 에 없다. bastion 으로 먼저 연다:
    ssh -f -N -D ${SOCKS_PORT} innogrid-icn-idc-bastion"
    fi
}

ensure_network() {
    docker network inspect "$NETWORK" >/dev/null 2>&1 && return
    docker network create --subnet "$SUBNET" --gateway "$GATEWAY" "$NETWORK" >/dev/null
    echo "  네트워크 생성: $NETWORK ($SUBNET)"
}

up() {
    [ $# -gt 0 ] || die "노드 IP 를 하나 이상 넘겨야 한다"
    require_socks
    ensure_network
    for ip in "$@"; do
        name="os-jump-${ip##*.}"
        docker rm -f "$name" >/dev/null 2>&1 || true
        docker run -d --name "$name" --network "$NETWORK" --ip "$ip" \
            --restart unless-stopped alpine/socat:latest \
            -d "TCP-LISTEN:22,fork,reuseaddr" \
            "SOCKS4A:host.docker.internal:${ip}:22,socksport=${SOCKS_PORT}" >/dev/null
        echo "  터널: $ip → $name"
    done
    attach
}

attach() {
    ensure_network
    for c in $ATTACH_CONTAINERS; do
        docker inspect "$c" >/dev/null 2>&1 || continue
        docker network connect "$NETWORK" "$c" 2>/dev/null && echo "  연결: $c" || echo "  이미 연결됨: $c"
    done
}

status() {
    echo "  SOCKS :${SOCKS_PORT} $(nc -z 127.0.0.1 "$SOCKS_PORT" 2>/dev/null && echo 열림 || echo 닫힘)"
    docker ps --filter "name=os-jump-" --format '  터널 {{.Names}} {{.Status}}'
    for c in $ATTACH_CONTAINERS; do
        docker inspect "$c" --format "  {{.Name}} networks: {{range \$k,\$v := .NetworkSettings.Networks}}{{\$k}} {{end}}" 2>/dev/null || true
    done
}

down() {
    docker ps -aq --filter "name=os-jump-" | xargs -r docker rm -f >/dev/null
    echo "  터널 정리 완료"
}

case "${1:-status}" in
    up) shift; up "$@" ;;
    attach) attach ;;
    status) status ;;
    down) down ;;
    *) die "알 수 없는 명령: $1 (up|attach|status|down)" ;;
esac
