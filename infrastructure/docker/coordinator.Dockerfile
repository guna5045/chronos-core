# Stage 1: Build Chronos Coordinator
FROM maven:3.9.9-eclipse-temurin-21-alpine AS builder
WORKDIR /workspace

# Cache dependencies
COPY pom.xml ./
COPY chronos-common/pom.xml ./chronos-common/
COPY chronos-ai/pom.xml ./chronos-ai/
COPY chronos-coordinator/pom.xml ./chronos-coordinator/
COPY chronos-worker/pom.xml ./chronos-worker/

RUN mvn dependency:go-offline -B -pl chronos-coordinator -am

# Copy source trees
COPY chronos-common/src ./chronos-common/src
COPY chronos-ai/src ./chronos-ai/src
COPY chronos-coordinator/src ./chronos-coordinator/src

# Package coordinator jar
RUN mvn package -DskipTests -B -pl chronos-coordinator -am

# Stage 2: Minimal Distroless/Alpine Runtime
FROM eclipse-temurin:21-jre-alpine AS runtime

# Security: Create non-root system user and group
RUN addgroup -S chronos && adduser -S chronos -G chronos

WORKDIR /app

COPY --from=builder /workspace/chronos-coordinator/target/chronos-coordinator-*.jar /app/chronos-coordinator.jar

RUN chown -R chronos:chronos /app

USER chronos:chronos

# HTTP/REST/Actuator & gRPC Coordination
EXPOSE 8080 9090

# Standard JVM container options
ENV JAVA_OPTS="-XX:+UseContainerSupport -XX:MaxRAMPercentage=75.0 -XX:+ExitOnOutOfMemoryError"
ENV SERVER_PORT=8080
ENV SPRING_PROFILES_ACTIVE=prod

# Healthcheck on Spring Boot Actuator liveness endpoint
HEALTHCHECK --interval=10s --timeout=5s --start-period=30s --retries=3 \
  CMD wget --no-verbose --tries=1 --spider http://localhost:8080/actuator/health/liveness || exit 1

ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/chronos-coordinator.jar"]
