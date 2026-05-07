# Server A 컨테이너 — 1 vCPU / 2 GB RAM 제약 환경에서 실행 (CLAUDE.md §2).
# Multi-stage 단순화 — 호스트에서 ./gradlew :server-a:bootJar 한 결과를 그대로 COPY.
# (CI / 평가자 입장에서 Dockerfile 만 보고 빌드 흐름을 따라가기 쉽게 분리한 결정.)

FROM eclipse-temurin:21-jre-alpine

WORKDIR /app

# bootJar 산출물 — 빌드 컨텍스트는 저장소 루트 (docker-compose 의 build.context: .).
ARG JAR_FILE=server-a/build/libs/server-a-*.jar
COPY ${JAR_FILE} /app/server-a.jar

EXPOSE 8080

# JVM 옵션:
# - UseContainerSupport (Java 21 default true) 로 cpus / memory limit 자동 검출.
# - MaxRAMPercentage=70 → 2 GB 컨테이너에서 heap ~1.4 GB. 나머지 ~600 MB 는 OS/native/metaspace.
# - G1GC — 1 vCPU 환경에서 ParallelGC 보다 latency 안정적.
# - urandom — ECDSA / SecureRandom 부팅 시 entropy 부족 회피.
ENTRYPOINT ["java", \
    "-XX:MaxRAMPercentage=70.0", \
    "-XX:+UseG1GC", \
    "-Djava.security.egd=file:/dev/./urandom", \
    "-jar", "/app/server-a.jar"]
