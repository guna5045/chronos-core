FROM eclipse-temurin:21-jre-alpine
RUN addgroup -S chronos && adduser -S chronos -G chronos
WORKDIR /app
COPY target/chronos-coordinator-*.jar /app/chronos-coordinator.jar
RUN chown -R chronos:chronos /app
USER chronos:chronos
EXPOSE 8080 9090
ENV JAVA_OPTS="-XX:+UseContainerSupport -XX:MaxRAMPercentage=75.0 -XX:+ExitOnOutOfMemoryError"
ENV SERVER_PORT=8080
HEALTHCHECK --interval=10s --timeout=5s --start-period=30s --retries=3 \
  CMD wget --no-verbose --tries=1 --spider http://localhost:8080/actuator/health/liveness || exit 1
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/chronos-coordinator.jar"]
