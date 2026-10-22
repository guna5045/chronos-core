FROM eclipse-temurin:21-jre-alpine
RUN addgroup -S chronos && adduser -S chronos -G chronos
WORKDIR /app
COPY target/chronos-worker-*.jar /app/chronos-worker.jar
RUN chown -R chronos:chronos /app
USER chronos:chronos
EXPOSE 8081 9091
ENV JAVA_OPTS="-XX:+UseContainerSupport -XX:MaxRAMPercentage=75.0 -XX:+ExitOnOutOfMemoryError"
ENV SERVER_PORT=8081
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/chronos-worker.jar"]
