# ============= Compose =============
#   docker-compose.dev.yml — local source 빌드, Pulumi 통합 full stack (dev/integration)
#   docker-compose.yml     — Docker Hub image pull only, production-like (demo/외부 평가)
COMPOSE      := docker compose -f docker-compose.dev.yml
COMPOSE_PROD := docker compose -f docker-compose.yml

# ============= Agent image / chart publishing =============
# 기본 값은 dev 용. CI 또는 다른 사용자는 env 또는 make 인자로 override.
#   make agent-publish DOCKER_USER=consine2c IMAGE_TAG=dev CHART_VERSION=0.1.0
DOCKER_USER     ?= consine2c
AGENT_IMAGE     ?= cluster-agent
IMAGE_TAG       ?= dev
# Multi-arch default — published image (cluster-agent, backup-agent, backend) 가 amd64 + arm64
# 둘 다 지원. Apple Silicon (arm64) / 일반 cloud VM (amd64) 양쪽 cluster 에서 동일 image manifest
# 가 동작. local dev 에서 single-arch 만 원하면 IMAGE_PLATFORMS=linux/amd64 로 override.
# build 시간이 ~2배 (QEMU emulation) 늘어나니 dev iteration 빠르게 하려면 local override 권장.
IMAGE_PLATFORMS ?= linux/amd64,linux/arm64

# Pulumi binary build tag — providers/factory_<csp>.go 의 //go:build <csp> || all selector.
# 운영 default 는 모든 CSP 컴파일 (all). dev iteration 가속 시 특정 CSP 만:
#   make backend-image PULUMI_BUILD_TAGS=aws,gcp
# 미등록 CSP runtime 호출 시 binary 가 친절한 rebuild 가이드 error 반환.
PULUMI_BUILD_TAGS ?= all

# host 의 docker 데몬에서 buildx 가 push 한 image 이름.
FULL_IMAGE      := $(DOCKER_USER)/$(AGENT_IMAGE):$(IMAGE_TAG)

# ============= Backend image =============
# docker-compose.yml 의 ANYCLOUD_BACKEND_IMAGE 와 일치해야 한다.
# 기본값 aipaas/anycloud-backend — 사용자 namespace 로 override 가능.
#   make backend-publish BACKEND_DOCKER_USER=consine2c BACKEND_IMAGE_TAG=dev
BACKEND_DOCKER_USER ?= aipaas
BACKEND_IMAGE       ?= anycloud-backend
BACKEND_IMAGE_TAG   ?= latest
FULL_BACKEND_IMAGE  := $(BACKEND_DOCKER_USER)/$(BACKEND_IMAGE):$(BACKEND_IMAGE_TAG)

# ============= Backup-agent image =============
# cluster-agent 와 같은 apps/agent Dockerfile 을 CMD_TARGET build arg 로 분기 빌드.
# 기본 image 이름 backup-agent (Helm chart values 의 image.repository 와 일치).
#   make backup-agent-image BACKUP_AGENT_DOCKER_USER=consine2c BACKUP_AGENT_IMAGE_TAG=dev
BACKUP_AGENT_DOCKER_USER ?= aipaas
BACKUP_AGENT_IMAGE       ?= backup-agent
BACKUP_AGENT_IMAGE_TAG   ?= latest
FULL_BACKUP_AGENT_IMAGE  := $(BACKUP_AGENT_DOCKER_USER)/$(BACKUP_AGENT_IMAGE):$(BACKUP_AGENT_IMAGE_TAG)

# ============= Buildx builder =============
# IMAGE_PLATFORMS 에 콤마가 있을 때만 자동 생성/사용 (기본 docker driver 는 single-arch 만 지원).
# docker-container driver 는 buildkit 컨테이너 안에서 실행돼 multi-arch 가능.
BUILDX_BUILDER  ?= anycloud-multiarch

# ============= Chart publishing =============
# chartmuseum 위치 — docker-compose.dev.yml 의 chartmuseum service 와 일치.
CHART_DIR             := apps/agent/deploy/helm/cluster-agent
# Chart version — dev iteration 동안 0.1.0 에 고정. ChartMuseum 의 ALLOW_OVERWRITE=true 가
# 동일 version 의 재 push 를 허용 → Bruno body 의 "version": "0.1.0" 는 영구 동일하게 두고,
# 매 publish 마다 chart 내용만 갈아치움. 첫 release 시점에 unfreeze.
CHART_VERSION         ?= 0.1.0
# 매 publish 의 실체 추적용 appVersion — git short SHA 자동 주입. helm install/list/history
# 에서 "지금 deployed 된 게 어느 build" 인지 즉시 확인 가능.
#   helm list → APP VERSION 컬럼에 표시됨
#   helm history → revision 별 추적
# 의도적으로 ?= 안 씀 (매번 git 호출). dirty tree 면 "<sha>-dirty" suffix.
CHART_APP_VERSION     := $(shell git rev-parse --short HEAD 2>/dev/null || echo dev)$(shell git diff --quiet 2>/dev/null || echo -dirty)
CHART_NAME            := cluster-agent
CHART_TGZ             := /tmp/$(CHART_NAME)-$(CHART_VERSION).tgz
CHARTMUSEUM_URL       ?= http://localhost:8080
CHARTMUSEUM_USER      ?= anycloud
CHARTMUSEUM_PASS      ?= dev-only-pass-do-not-use-in-prod

# ============= Manifest snapshot =============
# Helm chart 의 rendered output 을 raw manifest 로 저장 — apps/agent/deploy/k8s/agent.yaml.
# 본 파일은 사용자가 manual kubectl apply 또는 reference 로 사용. backend 의 자동 install 은
# 본 파일을 안 읽음 (AgentChartRenderer 가 chart 를 직접 helm template 함).
# Chart 변경 시 본 target 으로 갱신 권장 — PR review 시 diff 보기 쉬움.
SNAPSHOT_NS                   ?= aipaas-system
SNAPSHOT_TOKEN                ?= REPLACE_WITH_REAL_JWT
SNAPSHOT_BACKEND_GRPC         ?= host.docker.internal:9090
SNAPSHOT_IMAGE_REPO           ?= aipaas/cluster-agent
SNAPSHOT_IMAGE_TAG            ?= dev
SNAPSHOT_OUT                  := apps/agent/deploy/k8s/agent.yaml

# ============= .PHONY =============
.PHONY: help bootstrap-dev format format-check hooks-install
.PHONY: dev-up dev-down dev-reset dev-restart dev-logs dev-run dev-debug
.PHONY: secrets-warn secrets-check
.PHONY: prod-up prod-down prod-pull
.PHONY: test-up test-down test-reset test-logs test-rebuild
.PHONY: proto-lint proto-gen
.PHONY: buildx-setup
.PHONY: agent-build agent-test agent-image backup-agent-image
.PHONY: agent-chart-package agent-chart-push agent-publish agent-manifest-snapshot
.PHONY: backend-build backend-test backend-image backend-publish
.PHONY: test-unit test-integration test-coverage
.PHONY: pulumi-build
.PHONY: all-build all-test

# ============= help =============

help:
	@echo "Common targets (* = first-run cold timings):"
	@echo ""
	@echo "  hooks-install    .githooks/ 를 git core.hooksPath 로 등록 (pre-commit: spotless+buf lint)  [<1s]"
	@echo "  bootstrap-dev    .env 생성 + random secret 주입 + dev stack 기동 + healthcheck   [* ~3-5m]"
	@echo "  format           spotlessApply — Palantir Java Format 자동 적용                  [~5s]"
	@echo "  format-check     spotlessCheck — 포맷 위반 시 fail (CI 동일)                     [~5s]"
	@echo ""
	@echo "  dev-up           Pulumi 통합 full stack 기동 (DB + RabbitMQ + ChartMuseum + RustFS + OpenBao + backend + worker)  [* ~3m]"
	@echo "                   local source 빌드 (Dockerfile.pulumi). 모든 env 에 dev default 있어 export 없이 동작."
	@echo "  dev-down         정지 (volume 보존)"
	@echo "  dev-reset        정지 + volume 제거 (완전 초기화)"
	@echo "  dev-restart      backend + worker 만 재빌드/재기동 (의존 서비스 유지)"
	@echo "  dev-logs         backend 로그 follow"
	@echo "  dev-run          host JVM 으로 backend 직접 실행 (의존 서비스는 dev-up 필요)"
	@echo "  dev-debug        host JVM + JDWP :5005 listen — IDE remote attach"
	@echo "  secrets-warn     dev default 사용 중인 env 경고 (기동은 차단 X)"
	@echo "  secrets-check    필수 env 모두 export 되었는지 strict 검증 (prod-up 의존성)"
	@echo "  test-*           dev-* 의 alias (test-up/down/reset/logs/rebuild)"
	@echo ""
	@echo "  prod-up          docker hub image pull only (aipaas/anycloud-backend) — 필수 env 미설정 시 fail-fast"
	@echo "  prod-down        production 스택 정지"
	@echo "  prod-pull        production image 만 최신 pull (기동 X)"
	@echo ""
	@echo "  backend-image    Docker buildx → Hub push (aipaas/anycloud-backend:\$$BACKEND_IMAGE_TAG)"
	@echo "  backend-publish  alias for backend-image"
	@echo ""
	@echo "  proto-lint       buf lint                                                      [~2s]"
	@echo "  proto-gen        buf generate (apps/agent Go + apps/anycloud Java)             [~10s]"
	@echo ""
	@echo "  agent-build      Go build cluster agent (apps/agent)                           [* ~30s / +5s incr]"
	@echo "  agent-test       Go test (apps/agent)                                          [~20s]"
	@echo "  agent-image      Docker buildx → Hub push ($(FULL_IMAGE))"
	@echo "  backup-agent-image  Docker buildx → Hub push ($(FULL_BACKUP_AGENT_IMAGE))"
	@echo "  agent-chart-package  helm package (apps/agent/deploy/helm/cluster-agent)"
	@echo "  agent-chart-push     curl 로 chartmuseum 에 push ($(CHARTMUSEUM_URL))"
	@echo "  agent-publish    image + chart 를 한 번에 (image push → chart package → chart push)"
	@echo "  agent-manifest-snapshot  helm template 으로 raw manifest 갱신 (apps/agent/deploy/k8s/agent.yaml)"
	@echo "  backend-build    Gradle compile (anycloud)                                     [* ~2m / +20s incr]"
	@echo "  backend-test     Gradle test (anycloud) — full suite                           [* ~3-5m / +40s incr]"
	@echo "  test-unit        backend unit tests only (no Testcontainers)                   [~30s]"
	@echo "  test-integration backend integration tests (*IntegrationTest, Testcontainers)  [~3m]"
	@echo "  test-coverage    backend-test + jacocoTestReport + HTML open                   [* ~5m]"
	@echo "  pulumi-build     Pulumi Go build (infra/pulumi)                                [* ~2m / +10s incr]"
	@echo ""
	@echo "  all-build        backend + pulumi + agent build                                [* ~5m]"
	@echo "  all-test         backend-test + agent-test                                     [* ~6m]"

# ============= Dev infra =============

dev-up: secrets-warn
	$(COMPOSE) up -d --build --wait --wait-timeout 300
	@echo ""
	@echo "Services available (healthcheck 통과 확인됨):"
	@echo "  Backend REST:   http://localhost:8888  (Swagger: http://localhost:8888/swagger-ui.html)"
	@echo "  Backend gRPC:   localhost:9090  (agent dial-in)"
	@echo "  MariaDB:        localhost:3306  (user=anycloud, db=anycloud)"
	@echo "  RabbitMQ AMQP:  localhost:5672"
	@echo "  RabbitMQ UI:    http://localhost:15672"
	@echo "  ChartMuseum:    http://localhost:8080  (backend 입장: http://chartmuseum:8080)"
	@echo "  RustFS (S3):    http://localhost:9000  (console: http://localhost:9001)"
	@echo "  OpenBao:        http://localhost:8200"

dev-down:
	$(COMPOSE) down

dev-reset:
	$(COMPOSE) down -v --remove-orphans

# IDE/터미널에서 host JVM 으로 backend 직접 실행 — DB/RabbitMQ 등 의존성은 dev-up 컨테이너 가정.
# bootRun 은 코드 변경 시 spring-boot-devtools 가 hot-reload 처리.
dev-run:
	SPRING_PROFILES_ACTIVE=dev ./gradlew :anycloud:bootRun

# JDWP 5005 listen — IDE 의 remote attach 로 디버깅. suspend=n 이라 backend 부팅이 attach 를 기다리지 않음.
dev-debug:
	SPRING_PROFILES_ACTIVE=dev JAVA_OPTS="-agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=*:5005" ./gradlew :anycloud:bootRun

dev-restart:
	$(COMPOSE) up -d --build --no-deps anycloud-backend anycloud-bootstrap-worker

dev-logs:
	$(COMPOSE) logs -f anycloud-backend

# Dev default 가 docker-compose.dev.yml 에 박혀있어 env 미설정 OK. 운영 / 공유 stack 으로
# 옮길 때만 random secret 으로 override (cp .env.sample .env → openssl rand …).
secrets-warn:
	@used_defaults=0; \
	for v in RUSTFS_ROOT_PASSWORD OPENBAO_DEV_ROOT_TOKEN PULUMI_PASSPHRASE \
	         ANYCLOUD_AGENT_JWT_SECRET CSP_CREDENTIAL_ENCRYPTION_KEY; do \
	  eval "val=\$$$$v"; \
	  if [ -z "$$val" ]; then used_defaults=1; fi; \
	done; \
	if [ "$$used_defaults" = "1" ]; then \
	  echo "[info] using compose-baked dev defaults (env 미설정). 운영/공유는 .env 참고."; \
	fi

# Strict: 모든 필수 env exported 인지. prod-up 의존. dev-up 은 secrets-warn 만 사용.
secrets-check:
	@missing=0; \
	for v in DB_USER DB_PASS DB_ROOT_PASS RABBITMQ_USER RABBITMQ_PASS \
	         RUSTFS_ROOT_USER RUSTFS_ROOT_PASSWORD OPENBAO_DEV_ROOT_TOKEN \
	         PULUMI_PASSPHRASE ANYCLOUD_AGENT_JWT_SECRET \
	         CSP_CREDENTIAL_ENCRYPTION_KEY \
	         ANYCLOUD_AGENT_GRPC_PUBLIC_ENDPOINT ANYCLOUD_AGENT_HELM_REPO_URL; do \
	  eval "val=\$$$$v"; \
	  if [ -z "$$val" ]; then \
	    echo "missing env $$v"; \
	    missing=1; \
	  fi; \
	done; \
	if [ "$$missing" = "1" ]; then \
	  echo ""; \
	  echo "→ Production stack 은 모든 env 필수. .env.prod 작성 후 source."; \
	  exit 1; \
	fi

# ============= Production =============

# docker hub image pull only — local 빌드 없이 published image 만 사용.
prod-up: secrets-check
	$(COMPOSE_PROD) pull
	$(COMPOSE_PROD) up -d --wait --wait-timeout 300
	@echo ""
	@echo "Production stack up — image: $${ANYCLOUD_BACKEND_IMAGE:-aipaas/anycloud-backend}:$${ANYCLOUD_BACKEND_IMAGE_TAG:-latest}"

prod-down:
	$(COMPOSE_PROD) down

prod-pull:
	$(COMPOSE_PROD) pull

# ============= Test stack =============
# test-* 는 dev-* 의 alias — 통합 compose 라서 backend 가 항상 함께 뜸.
test-up: dev-up
test-down: dev-down
test-reset: dev-reset
test-logs: dev-logs
test-rebuild: dev-restart

# ============= Proto =============

proto-lint:
	cd proto && buf lint

proto-gen:
	cd proto && buf generate
	./gradlew :anycloud:generateProto

# ============= Buildx =============

# Multi-arch 빌드용 docker-container 드라이버 builder 자동 셋업.
# Default IMAGE_PLATFORMS 가 amd64 + arm64 multi-arch 이므로 보통 이 branch 가 동작.
# Single-arch override (IMAGE_PLATFORMS=linux/amd64) 면 기본 docker driver 사용.
# 이미 존재하면 재사용. orbstack/docker desktop 무관하게 동작.
# agent-image / backup-agent-image / backend-image 모두 본 target 을 의존.
buildx-setup:
	@if echo "$(IMAGE_PLATFORMS)" | grep -q ','; then \
		if ! docker buildx inspect $(BUILDX_BUILDER) >/dev/null 2>&1; then \
			echo "==> Creating multi-arch builder '$(BUILDX_BUILDER)' (docker-container driver)"; \
			docker buildx create --name $(BUILDX_BUILDER) --driver docker-container --use; \
			docker buildx inspect --bootstrap | tail -5; \
		else \
			echo "==> Using existing multi-arch builder '$(BUILDX_BUILDER)'"; \
			docker buildx use $(BUILDX_BUILDER); \
		fi; \
	else \
		echo "==> Single-arch override ($(IMAGE_PLATFORMS)) — default docker driver"; \
	fi

# ============= Agent =============

agent-build:
	cd apps/agent && go build -o bin/cluster-agent ./cmd/cluster-agent
	cd apps/agent && go build -o bin/backup-agent ./cmd/backup-agent

agent-test:
	cd apps/agent && go test ./...

# Docker buildx + push. Default 는 multi-arch (linux/amd64 + linux/arm64) — Apple Silicon
# (arm64) / 일반 cloud VM (amd64) 양쪽 cluster 에서 동일 image manifest 동작.
# Single-arch 만 빠르게 빌드하고 싶으면 IMAGE_PLATFORMS=linux/amd64 로 override.
# 사전 조건: docker login 이 완료된 상태.
agent-image: buildx-setup
	@echo "==> Building & pushing $(FULL_IMAGE) for $(IMAGE_PLATFORMS)"
	docker buildx build \
		--platform $(IMAGE_PLATFORMS) \
		--build-arg CMD_TARGET=cluster-agent \
		-t $(FULL_IMAGE) \
		-f apps/agent/Dockerfile \
		--push \
		apps/agent
	@echo "==> Pushed: $(FULL_IMAGE)"

# Backup-agent image — apps/agent/Dockerfile 의 CMD_TARGET=backup-agent build arg 로 동일 base
# 에서 다른 binary 만 빌드. helm chart (backup-agent) 의 image.repository 와 일치 필요.
backup-agent-image: buildx-setup
	@echo "==> Building & pushing $(FULL_BACKUP_AGENT_IMAGE) for $(IMAGE_PLATFORMS)"
	docker buildx build \
		--platform $(IMAGE_PLATFORMS) \
		--build-arg CMD_TARGET=backup-agent \
		-t $(FULL_BACKUP_AGENT_IMAGE) \
		-f apps/agent/Dockerfile \
		--push \
		apps/agent
	@echo "==> Pushed: $(FULL_BACKUP_AGENT_IMAGE)"

# Helm chart package — values.yaml 의 image.repository/tag 와 위 IMAGE_TAG 가 일치하도록 주의.
# CHART_VERSION 은 dev 동안 0.1.0 고정 (overwrite). CHART_APP_VERSION (git short SHA) 가
# 매 build 의 실체 식별자 — helm list / history 에서 어느 build 가 deployed 인지 즉시 확인.
agent-chart-package:
	@echo "==> Linting chart $(CHART_DIR)"
	helm lint $(CHART_DIR)
	@echo "==> Packaging chart → $(CHART_TGZ) (version=$(CHART_VERSION), appVersion=$(CHART_APP_VERSION))"
	helm package $(CHART_DIR) \
		--version $(CHART_VERSION) \
		--app-version $(CHART_APP_VERSION) \
		-d /tmp
	@/bin/ls -la $(CHART_TGZ)

# chartmuseum 에 push. ALLOW_OVERWRITE=true 라 같은 version 재 push 도 200.
# anycloud backend 는 컨테이너 네트워크 안에서 chartmuseum:8080 으로 reach — 본 push 는 host:8080.
agent-chart-push: agent-chart-package
	@echo "==> Pushing chart to $(CHARTMUSEUM_URL)"
	@curl --fail --show-error -s \
		-u "$(CHARTMUSEUM_USER):$(CHARTMUSEUM_PASS)" \
		--data-binary @$(CHART_TGZ) \
		$(CHARTMUSEUM_URL)/api/charts
	@echo ""
	@echo "==> Done. Verify:"
	@echo "    curl -u $(CHARTMUSEUM_USER):*** $(CHARTMUSEUM_URL)/api/charts/$(CHART_NAME) | jq"

# 한 방. image → chart package → chart push.
# 호출 예:
#   make agent-publish DOCKER_USER=consine2c IMAGE_TAG=dev
#   make agent-publish CHART_VERSION=0.2.0 IMAGE_TAG=v0.2.0
agent-publish: agent-image agent-chart-push
	@echo ""
	@echo "==> All published:"
	@echo "    Image: $(FULL_IMAGE)"
	@echo "    Chart: $(CHART_NAME)-$(CHART_VERSION).tgz → $(CHARTMUSEUM_URL)"

agent-manifest-snapshot:
	@echo "==> Rendering chart to raw manifest snapshot → $(SNAPSHOT_OUT)"
	@printf '%s\n' \
		'# AUTO-GENERATED — DO NOT EDIT. Source: apps/agent/deploy/helm/cluster-agent/' \
		'# Regenerate: make agent-manifest-snapshot' \
		'# Backend 의 자동 install 은 본 파일을 안 읽음 (AgentChartRenderer 가 chart 를 직접 렌더).' \
		'#' > $(SNAPSHOT_OUT)
	@helm template cluster-agent $(CHART_DIR) \
		--namespace $(SNAPSHOT_NS) \
		--set "bootstrap.registrationToken=$(SNAPSHOT_TOKEN)" \
		--set "backend.grpcAddr=$(SNAPSHOT_BACKEND_GRPC)" \
		--set "image.repository=$(SNAPSHOT_IMAGE_REPO)" \
		--set "image.tag=$(SNAPSHOT_IMAGE_TAG)" \
		--include-crds \
		>> $(SNAPSHOT_OUT)
	@echo "==> Updated $(SNAPSHOT_OUT) ($$(wc -l < $(SNAPSHOT_OUT)) lines)"

# ============= Backend =============

backend-build:
	./gradlew :anycloud:compileJava

backend-test:
	./gradlew :anycloud:test

# Unit tests only — *IntegrationTest 제외 (Testcontainers 미가동 → 빠른 inner loop).
# JUnit pattern: trailing '*' wildcard, exclude integration suite.
test-unit:
	./gradlew :anycloud:test --tests '*' --tests '!*IntegrationTest'

# Integration tests only — Testcontainers (mariadb + rabbitmq) booting 포함.
# AbstractIntegrationTest 상속 클래스가 대상. testcontainers reuse 활성화 시 2회차 부터 빠름.
test-integration:
	TESTCONTAINERS_REUSE_ENABLE=true ./gradlew :anycloud:test --tests '*IntegrationTest'

# Coverage report — jacoco HTML 자동 open. INSTRUCTION baseline 18% (build.gradle 참고).
test-coverage:
	./gradlew :anycloud:test :anycloud:jacocoTestReport
	@open apps/anycloud/build/reports/jacoco/test/html/index.html 2>/dev/null || \
		echo "Coverage report: apps/anycloud/build/reports/jacoco/test/html/index.html"

# Backend image — Dockerfile.pulumi (Pulumi 3.160 + Go + Helm 포함). docker-compose.yml 이
# 본 image 명을 ANYCLOUD_BACKEND_IMAGE 로 pull. agent-image 와 동일하게 buildx 사용.
# 사전 조건: docker login.
backend-image: buildx-setup
	@echo "==> Building & pushing $(FULL_BACKEND_IMAGE) for $(IMAGE_PLATFORMS) (tags=$(PULUMI_BUILD_TAGS))"
	docker buildx build \
		--platform $(IMAGE_PLATFORMS) \
		--build-arg PULUMI_BUILD_TAGS=$(PULUMI_BUILD_TAGS) \
		-t $(FULL_BACKEND_IMAGE) \
		-f Dockerfile.pulumi \
		--push \
		.
	@echo "==> Pushed: $(FULL_BACKEND_IMAGE)"

backend-publish: backend-image
	@echo ""
	@echo "==> Published: $(FULL_BACKEND_IMAGE)"

# ============= Format/Lint/Hooks =============

# Spotless: Palantir Java Format. ratchetFrom 'origin/main' 으로 변경 파일만 검사 (build.gradle).
format:
	./gradlew :anycloud:spotlessApply

format-check:
	./gradlew :anycloud:spotlessCheck

# git core.hooksPath 를 .githooks 로 가리키도록 설정 — repo 내 hook 이 모든 clone 에서 동일하게 동작.
# .githooks/pre-commit 이 spotless + buf lint + secret leak 휴리스틱 검사.
# 우회 (긴급): git commit --no-verify
hooks-install:
	@git config core.hooksPath .githooks
	@echo "core.hooksPath → .githooks (pre-commit 자동 활성화)"
	@echo "  훅 우회: git commit --no-verify"

# ============= Onboarding =============

# 신규 개발자 첫 setup — .env 자동 생성 + random secret + dev stack up + healthcheck.
# 멱등 — 이미 .env 있으면 무시. scripts/bootstrap-dev.sh 가 실체.
bootstrap-dev:
	./scripts/bootstrap-dev.sh

# ============= Pulumi =============

pulumi-build:
	cd infra/pulumi && go build ./...

# ============= Aggregate =============

all-build: backend-build pulumi-build agent-build

all-test: backend-test agent-test
