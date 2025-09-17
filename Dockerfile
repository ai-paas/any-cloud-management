FROM eclipse-temurin:17.0.7_7-jdk AS builder
LABEL structBase.authors="https://github.com/taking/java-spring-base-structure"
LABEL com.aipaas.anycloud.backend.authors="https://github.com/ai-paas/any-cloud-management"

COPY gradlew .
COPY gradle gradle
COPY build.gradle .
COPY settings.gradle .
COPY anycloud anycloud

COPY application.properties_docker anycloud/src/main/resources/application.properties

RUN chmod +x ./gradlew
RUN ./gradlew bootJar

FROM eclipse-temurin:17.0.7_7-jdk
COPY helm/helm-v3.19.0-linux-amd64.tar.gz /tmp/
RUN tar -xzf /tmp/helm-v3.19.0-linux-amd64.tar.gz -C /tmp/ \
    && mv /tmp/linux-amd64/helm /usr/local/bin/helm \
    && chmod +x /usr/local/bin/helm \
    && rm -rf /tmp/helm-v3.19.0-linux-amd64.tar.gz /tmp/linux-amd64

COPY --from=builder anycloud/build/libs/*-SNAPSHOT.jar app.jar

EXPOSE 8888
ENTRYPOINT ["sh", "-c", "java ${JAVA_OPTS} -jar /app.jar ${0} ${@}"]