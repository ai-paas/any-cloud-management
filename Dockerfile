# Build stage
FROM eclipse-temurin:25-jdk AS builder

# Metadata
LABEL structBase.authors="https://github.com/taking/java-spring-base-structure"
LABEL com.aipaas.anycloud.backend.authors="https://github.com/ai-paas/any-cloud-management"
LABEL version="0.0.1-SNAPSHOT"

# Set working directory
WORKDIR /app

# Copy Gradle wrapper and configuration files first (for better layer caching)
COPY gradlew gradlew.bat ./
COPY gradle/ gradle/
COPY build.gradle settings.gradle ./

# Make gradlew executable
RUN chmod +x ./gradlew

# gradle wrapper distribution 부트스트랩 — slow link 대비 3회 backoff retry.
# 본 RUN 이 services.gradle.org 의 gradle-X.Y.Z-bin.zip 을 가져오므로 첫 빌드에서 가장 fragile.
# BuildKit cache mount 는 아래 bootJar RUN 에서 도입.
RUN for i in 1 2 3; do \
        ./gradlew --version --no-daemon && break || \
        { echo "gradle wrapper bootstrap failed (attempt $i/3), retry in 10s..."; sleep 10; }; \
    done

# Copy source code (apps/anycloud + starter libs — gradle multi-module dependency).
COPY apps/anycloud/ apps/anycloud/
COPY libs/cluster-agent-spring-boot-starter/ libs/cluster-agent-spring-boot-starter/
# RBAC / Backup / Observability 3 feature 통합 (cluster-agent-features-spring-boot-starter).
COPY libs/cluster-agent-features-spring-boot-starter/ libs/cluster-agent-features-spring-boot-starter/
# provisioning starter — ProvisioningOutput / PulumiCommandResult / ProvisionEventBus /
# PulumiCommandService / ProvisioningOutputMapper / ProvisioningOutputValidationException 등
# anycloud 의존 의 source.
COPY libs/cluster-provisioning-spring-boot-starter/ libs/cluster-provisioning-spring-boot-starter/
# cluster-agent helm chart bundling (build.gradle copyAgentChartResources).
COPY apps/agent/deploy/helm/ apps/agent/deploy/helm/

# Build the application.
# 컨테이너 환경 설정은 apps/anycloud/src/main/resources/application-docker.yaml 이 담당 —
# 아래 ENV SPRING_PROFILES_ACTIVE=docker 가 활성화. (이전: application.properties_docker
# 를 COPY 로 덮어쓰던 방식. .properties 가 .yaml 보다 우선이라 yaml 의 도메인 설정이
# 잘 보였는데, 이제 docker profile 도 yaml 로 단일화.)
RUN --mount=type=cache,target=/root/.gradle \
    ./gradlew bootJar --no-daemon --info

# Runtime stage
FROM eclipse-temurin:25-jre

# Install Helm
COPY infra/helm/helm-v3.19.0-linux-amd64.tar.gz /tmp/
RUN tar -xzf /tmp/helm-v3.19.0-linux-amd64.tar.gz -C /tmp/ \
    && mv /tmp/linux-amd64/helm /usr/local/bin/helm \
    && chmod +x /usr/local/bin/helm \
    && rm -rf /tmp/helm-v3.19.0-linux-amd64.tar.gz /tmp/linux-amd64

# Create non-root user for security
RUN groupadd -r anycloud && useradd -r -g anycloud anycloud

# Set working directory
WORKDIR /app

# Copy the built JAR from builder stage
COPY --from=builder /app/apps/anycloud/build/libs/*-SNAPSHOT.jar app.jar

# Change ownership to non-root user
RUN chown -R anycloud:anycloud /app

# Override XDG paths so Helm doesn't touch /home/anycloud
ENV XDG_CACHE_HOME=/app/.cache \
    XDG_CONFIG_HOME=/app/.config \
    XDG_DATA_HOME=/app/.local/share

# Switch to non-root user
USER anycloud

# Expose port
EXPOSE 8888


# Set JVM options for better performance
ENV JAVA_OPTS="-XX:+UseContainerSupport -XX:MaxRAMPercentage=75.0 -XX:+UseG1GC -XX:+UseStringDeduplication"

# 컨테이너 환경 설정 활성화 — application-docker.yaml 이 jar 안의 classpath 에 포함됨.
# 운영자가 SPRING_PROFILES_ACTIVE=docker,prod 등으로 multi-profile composition 가능.
ENV SPRING_PROFILES_ACTIVE=docker

# Run the application
ENTRYPOINT ["sh", "-c", "java ${JAVA_OPTS} -jar /app/app.jar ${0} ${@}"]