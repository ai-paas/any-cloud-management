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
COPY --from=builder anycloud/build/libs/*-SNAPSHOT.jar app.jar

EXPOSE 8888
ENTRYPOINT ["sh", "-c", "java ${JAVA_OPTS} -jar /app.jar ${0} ${@}"]