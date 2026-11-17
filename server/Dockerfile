# syntax=docker/dockerfile:1
FROM eclipse-temurin:25-jdk AS build
WORKDIR /workspace
COPY gradlew settings.gradle.kts build.gradle.kts ./
COPY gradle gradle
RUN --mount=type=cache,target=/root/.gradle ./gradlew --no-daemon dependencies -q > /dev/null
COPY src src
ARG RACHA_VERSION=0.0.1-SNAPSHOT
RUN --mount=type=cache,target=/root/.gradle ./gradlew --no-daemon bootJar -x test -Pracha.version=${RACHA_VERSION} \
    && cp "$(ls build/libs/*.jar | grep -v -- '-plain\.jar$' | head -n 1)" /workspace/app.jar

FROM eclipse-temurin:25-jre-alpine
RUN addgroup -S racha && adduser -S racha -G racha && mkdir -p /app/logs && chown racha:racha /app/logs
WORKDIR /app
COPY --from=build /workspace/app.jar app.jar
ENV RACHA_LOG_FILE=/app/logs/racha.log
USER racha
EXPOSE 8080
HEALTHCHECK --interval=10s --timeout=3s --start-period=30s --retries=5 \
    CMD wget -qO- http://localhost:8080/actuator/health/liveness || exit 1
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "app.jar"]
