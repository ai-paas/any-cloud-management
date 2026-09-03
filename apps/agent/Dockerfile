# Multi-stage build — distroless 로 최소 attack surface.
#
# 단일 Dockerfile 로 cluster-agent / backup-agent 두 binary 모두 빌드. CMD_TARGET build arg 로
# 선택. Helm chart 별로 별도 image 가 push 되도록 CI 가 두 번 호출 (각 target):
#
#   docker buildx build --build-arg CMD_TARGET=cluster-agent -t ...cluster-agent:tag ...
#   docker buildx build --build-arg CMD_TARGET=backup-agent  -t ...backup-agent:tag ...
#
# backup-agent 는 etcdctl 가 PATH 에 있어야 함 (etcd snapshot save). etcd 공식 release archive
# 에서 추출해 final image 의 /usr/local/bin/etcdctl 에 동봉. cluster-agent target 은 stub script
# 로 image layout 일관성 유지.

# go.mod 의 go 지시자와 맞춘다. k8s.io/* 0.37 과 helm 3.21 이 go 1.26.0 을 요구해
# go mod tidy 가 올렸고, 여기가 낮으면 go build 가 거부한다.
# publish-agents.yml 은 이 값을 넘기지 않으므로 기본값이 곧 릴리스 빌드 버전이다.
ARG GO_VERSION=1.26
ARG ALPINE_VERSION=3.20
ARG ETCD_VERSION=v3.5.16

# --platform=$BUILDPLATFORM: builder 는 host arch 그대로 두고 (QEMU emulation 회피),
# go build 가 cross-compile (GOARCH=$TARGETARCH) 하도록 함. buildx multi-platform 빌드 시 amd64+arm64
# 둘 다 native build 속도 (CI 의 amd64 runner 가 arm64 emulation 대신 cross-compile).
FROM --platform=$BUILDPLATFORM golang:${GO_VERSION}-alpine AS builder

# buildx 가 자동 주입하는 platform 변수.
#   BUILDPLATFORM: builder 의 platform (예: linux/amd64)
#   TARGETPLATFORM / TARGETOS / TARGETARCH: 빌드 결과의 target (예: linux/arm64 / linux / arm64)
ARG TARGETOS
ARG TARGETARCH

WORKDIR /src

# Cache dependency layer
COPY go.mod go.sum* ./
RUN go mod download

COPY . .

# CGO_ENABLED=0 — distroless static base 용. Helm SDK 통합 시 일부 native dep 있으면 재검토.
ARG VERSION=dev
ARG CMD_TARGET=cluster-agent
RUN CGO_ENABLED=0 GOOS=${TARGETOS} GOARCH=${TARGETARCH} go build \
    -ldflags="-s -w -X main.buildVersionStr=${VERSION}" \
    -o /out/binary ./cmd/${CMD_TARGET}

# Etcdctl source — backup-agent 만 실 binary, 다른 target 은 stub.
# 단일 stage 로 image layout 동일 유지 (final 의 COPY 가 stage 이름 동적으로 못 받음).
FROM alpine:${ALPINE_VERSION} AS etcdctl-source
ARG TARGETARCH
ARG CMD_TARGET=cluster-agent
ARG ETCD_VERSION
RUN apk add --no-cache wget tar ca-certificates && \
    mkdir -p /etcdctl && \
    if [ "$CMD_TARGET" = "backup-agent" ]; then \
      wget -qO /tmp/etcd.tar.gz "https://github.com/etcd-io/etcd/releases/download/${ETCD_VERSION}/etcd-${ETCD_VERSION}-linux-${TARGETARCH}.tar.gz" && \
      tar -xz -C /tmp -f /tmp/etcd.tar.gz && \
      mv "/tmp/etcd-${ETCD_VERSION}-linux-${TARGETARCH}/etcdctl" /etcdctl/etcdctl && \
      chmod 0755 /etcdctl/etcdctl ; \
    else \
      printf '#!/bin/sh\necho "etcdctl not bundled in this image (CMD_TARGET=cluster-agent)" >&2; exit 127\n' > /etcdctl/etcdctl && \
      chmod 0755 /etcdctl/etcdctl ; \
    fi

# Runtime — distroless static (root variant).
# backup-agent 가 /etc/kubernetes/pki/etcd/healthcheck-client.key (mode 0600 root:root) 를 읽어
# etcd 에 client TLS 로 접속. nonroot (uid 65534) 로는 0600 cert 못 읽음.
# 대신 DaemonSet SecurityContext 에서 cap drop ALL + allowPrivilegeEscalation:false +
# readOnlyRootFilesystem 으로 ambient capability 좁힘 — root 지만 ambient 권한 최소.
# cluster-agent target 은 별도 SecurityContext 로 nonroot 강제 가능 (인-cluster 모드 등).
FROM gcr.io/distroless/static-debian12:latest

WORKDIR /app
COPY --from=builder /out/binary /app/binary
COPY --from=etcdctl-source /etcdctl/etcdctl /usr/local/bin/etcdctl

ENTRYPOINT ["/app/binary"]
