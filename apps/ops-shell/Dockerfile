# 웹 터미널이 붙는 관리 파드 이미지 — kubectl 과 k9s 를 함께 담는다.
#
# 둘 다 담긴 공식 이미지가 없다. bitnami/kubectl 은 kubectl 만, derailed/k9s 는 k9s 만 있어
# 터미널에서 둘을 오갈 수 없다.
#
# 버전은 build-arg 로 고정한다. latest 를 그대로 끌어오면 같은 태그가 어느 날 다른 것을
# 받아와, 문제가 생겼을 때 무엇이 바뀌었는지 알 수 없다.

ARG KUBECTL_VERSION=v1.37.0
ARG K9S_VERSION=v0.51.0

FROM alpine:3.21 AS fetch
ARG KUBECTL_VERSION
ARG K9S_VERSION
# TARGETARCH 는 buildx 가 채운다 (amd64 / arm64). 손으로 분기하면 멀티아키에서 틀린다.
ARG TARGETARCH

RUN apk add --no-cache curl tar ca-certificates

RUN set -eux; \
    curl -fsSLo /tmp/kubectl \
      "https://dl.k8s.io/release/${KUBECTL_VERSION}/bin/linux/${TARGETARCH}/kubectl"; \
    curl -fsSLo /tmp/kubectl.sha256 \
      "https://dl.k8s.io/release/${KUBECTL_VERSION}/bin/linux/${TARGETARCH}/kubectl.sha256"; \
    echo "$(cat /tmp/kubectl.sha256)  /tmp/kubectl" | sha256sum -c -; \
    chmod +x /tmp/kubectl

# k9s 는 자산 이름이 Linux_amd64 / Linux_arm64 로 대문자다.
RUN set -eux; \
    case "${TARGETARCH}" in \
      amd64) K9S_ARCH=Linux_amd64 ;; \
      arm64) K9S_ARCH=Linux_arm64 ;; \
      *) echo "지원하지 않는 아키텍처: ${TARGETARCH}" >&2; exit 1 ;; \
    esac; \
    curl -fsSLo /tmp/k9s.tar.gz \
      "https://github.com/derailed/k9s/releases/download/${K9S_VERSION}/k9s_${K9S_ARCH}.tar.gz"; \
    curl -fsSLo /tmp/k9s_checksums.sha256 \
      "https://github.com/derailed/k9s/releases/download/${K9S_VERSION}/checksums.sha256"; \
    # 줄 끝을 고정하지 않으면 같은 이름으로 시작하는 .sbom.json 줄까지 잡혀 검증이 실패한다.
    grep " k9s_${K9S_ARCH}\.tar\.gz$" /tmp/k9s_checksums.sha256 \
      | awk '{print $1 "  /tmp/k9s.tar.gz"}' | sha256sum -c -; \
    tar -xzf /tmp/k9s.tar.gz -C /tmp k9s; \
    chmod +x /tmp/k9s

FROM alpine:3.21
ARG KUBECTL_VERSION
ARG K9S_VERSION

# k9s 는 TUI 라 터미널 정의가 없으면 화면이 깨진다. bash 는 탭 완성과 히스토리 때문에 둔다.
RUN apk add --no-cache bash ca-certificates ncurses-terminfo-base less

COPY --from=fetch /tmp/kubectl /usr/local/bin/kubectl
COPY --from=fetch /tmp/k9s /usr/local/bin/k9s

# 파드 안에서 kubeconfig 없이 ServiceAccount 로 붙는다. HOME 이 없으면 k9s 가 설정을 못 쓴다.
ENV HOME=/home/ops \
    TERM=xterm-256color \
    KUBECONFIG=""

RUN adduser -D -h /home/ops -s /bin/bash ops
USER ops
WORKDIR /home/ops

LABEL org.opencontainers.image.title="anycloud ops-shell" \
      org.opencontainers.image.description="kubectl ${KUBECTL_VERSION} + k9s ${K9S_VERSION} — 웹 터미널용" \
      org.opencontainers.image.source="https://github.com/ai-paas/any-cloud-management"

CMD ["sleep", "infinity"]
