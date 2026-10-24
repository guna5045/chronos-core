# Stage 1: Build Chronos Worker
FROM maven:3.9.9-eclipse-temurin-21 AS builder
WORKDIR /workspace

COPY pom.xml ./
COPY chronos-common/pom.xml ./chronos-common/
COPY chronos-ai/pom.xml ./chronos-ai/
COPY chronos-coordinator/pom.xml ./chronos-coordinator/
COPY chronos-worker/pom.xml ./chronos-worker/

COPY chronos-common/src ./chronos-common/src
COPY chronos-ai/src ./chronos-ai/src
COPY chronos-worker/src ./chronos-worker/src

RUN mvn package -DskipTests -B -pl chronos-worker -am

# Stage 2: Minimal Alpine Runtime
FROM eclipse-temurin:21-jre-alpine AS runtime

RUN addgroup -S chronos && adduser -S chronos -G chronos

WORKDIR /app

COPY --from=builder /workspace/chronos-worker/target/chronos-worker-*.jar /app/chronos-worker.jar

RUN chown -R chronos:chronos /app

USER chronos:chronos

EXPOSE 8081 9091

ENV JAVA_OPTS="-XX:+UseContainerSupport -XX:MaxRAMPercentage=75.0 -XX:+ExitOnOutOfMemoryError"
ENV SERVER_PORT=8081

ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/chronos-worker.jar"]
