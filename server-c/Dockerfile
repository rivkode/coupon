# Server C 컨테이너 — 1 vCPU / 2 GB RAM 제약 환경에서 실행 (CLAUDE.md §2).
# Multi-stage 단순화 — 호스트에서 ./gradlew :server-c:bootJar 한 결과를 그대로 COPY.

FROM eclipse-temurin:21-jre-alpine

WORKDIR /app

ARG JAR_FILE=server-c/build/libs/server-c-*.jar
COPY ${JAR_FILE} /app/server-c.jar

EXPOSE 8082

ENTRYPOINT ["java", \
    "-XX:MaxRAMPercentage=70.0", \
    "-XX:+UseG1GC", \
    "-Djava.security.egd=file:/dev/./urandom", \
    "-jar", "/app/server-c.jar"]
