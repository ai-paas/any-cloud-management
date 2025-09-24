# Build stage
FROM eclipse-temurin:21-jdk AS builder

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

# Copy source code
COPY anycloud/ anycloud/

# Copy application properties
COPY application.properties_docker anycloud/src/main/resources/application.properties

# Build the application
RUN ./gradlew bootJar --no-daemon --info

# Runtime stage
FROM eclipse-temurin:21-jre

# Install Helm
COPY helm/helm-v3.19.0-linux-amd64.tar.gz /tmp/
RUN tar -xzf /tmp/helm-v3.19.0-linux-amd64.tar.gz -C /tmp/ \
    && mv /tmp/linux-amd64/helm /usr/local/bin/helm \
    && chmod +x /usr/local/bin/helm \
    && rm -rf /tmp/helm-v3.19.0-linux-amd64.tar.gz /tmp/linux-amd64

# Create non-root user for security
RUN groupadd -r anycloud && useradd -r -g anycloud anycloud

# Set working directory
WORKDIR /app

# Copy the built JAR from builder stage
COPY --from=builder /app/anycloud/build/libs/*-SNAPSHOT.jar app.jar

# Change ownership to non-root user
RUN chown -R anycloud:anycloud /app

# Switch to non-root user
USER anycloud

# Expose port
EXPOSE 8888


# Set JVM options for better performance
ENV JAVA_OPTS="-XX:+UseContainerSupport -XX:MaxRAMPercentage=75.0 -XX:+UseG1GC -XX:+UseStringDeduplication"

# Run the application
ENTRYPOINT ["sh", "-c", "java ${JAVA_OPTS} -jar /app/app.jar ${0} ${@}"]