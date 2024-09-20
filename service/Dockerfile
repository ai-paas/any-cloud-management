FROM azul/zulu-openjdk:17-jre AS builder
RUN useradd -m -s /bin/bash aipaas
WORKDIR /home/aipaas
COPY ../ .
COPY ../gradlew .
RUN chmod +x gradlew
RUN ./gradlew clean build --refresh-dependencies --stacktrace -x test
RUN chown -R aipaas:aipaas /home/aipaas

FROM azul/zulu-openjdk:17-jre
RUN useradd -m -s /bin/bash aipaas
ADD --chown=root:root --chmod=755 https://github.com/moparisthebest/static-curl/releases/latest/download/curl-amd64 /usr/bin/curl
RUN chmod +x /usr/bin/curl
USER aipaas
RUN chown -R aipaas:aipaas /home/aipaas
RUN chmod 755 /home/aipaas
WORKDIR /home/aipaas
COPY --from=builder /home/aipaas/service/build/libs/aipaas-api-service.jar .
CMD ["java", "-jar", "aipaas-api-service.jar"]
