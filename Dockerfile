# syntax=docker/dockerfile:1
# Build stage: same Gradle definition as developer and CI builds (wrapper + npm ci + tsc + esbuild).
FROM eclipse-temurin:21-jdk AS build
COPY --from=node:24-slim /usr/local/ /usr/local/
WORKDIR /src
COPY gradlew settings.gradle.kts build.gradle.kts ./
COPY gradle gradle
COPY frontend frontend
COPY conformance conformance
COPY src src
RUN chmod +x gradlew && ./gradlew --no-daemon bootJar -x test

# Runtime stage: JRE and the application only; no Node, Gradle or database service.
FROM eclipse-temurin:21-jre
ARG VERSION=dev
LABEL org.opencontainers.image.title="FolderSync" org.opencontainers.image.version="${VERSION}"
RUN apt-get update && apt-get install -y --no-install-recommends curl && rm -rf /var/lib/apt/lists/* \
 && groupadd --system --gid 10001 foldersync \
 && useradd --system --uid 10001 --gid foldersync --home-dir /app --shell /usr/sbin/nologin foldersync \
 && mkdir -p /app /data && chown foldersync:foldersync /data
COPY --from=build /src/build/libs/*.jar /app/app.jar
USER foldersync
WORKDIR /app
ENV FOLDERSYNC_DATA_DIR=/data \
    JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=70 -XX:+ExitOnOutOfMemoryError"
VOLUME /data
EXPOSE 8080
HEALTHCHECK --interval=30s --timeout=5s --start-period=60s --retries=3 \
  CMD curl -fsS http://127.0.0.1:8080/actuator/health/readiness || exit 1
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
