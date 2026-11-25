FROM eclipse-temurin:21-jdk-alpine AS build
WORKDIR /workspace
COPY gradlew ./
COPY gradle gradle
COPY build.gradle.kts settings.gradle.kts ./
RUN chmod +x gradlew || true
COPY src src
# Build with system gradle if wrapper jar missing
RUN if [ -f gradle/wrapper/gradle-wrapper.jar ]; then ./gradlew bootJar --no-daemon; \
    else apk add --no-cache gradle && gradle bootJar --no-daemon; fi

# glibc runtime: webp-imageio's writer loads a bundled glibc JNI library (musl/Alpine cannot).
FROM eclipse-temurin:21-jre AS runtime-base
WORKDIR /app
RUN groupadd --system app && useradd --system --gid app --home-dir /app --no-create-home app \
    && mkdir -p /data/media /tmp && chown -R app:app /data/media /tmp
COPY --from=build /workspace/build/libs/*.jar app.jar
ENV LANG=C.UTF-8 LC_ALL=C.UTF-8

# Self-contained test image: embedded PostgreSQL, fixed auth constants, local senders.
# Build from the server directory: docker build --target dummy -t bethanys-bakery-dummy .
FROM runtime-base AS dummy
COPY docker/entrypoint-dummy.sh /app/entrypoint-dummy.sh
RUN chmod +x /app/entrypoint-dummy.sh && chown app:app /app/entrypoint-dummy.sh
EXPOSE 8080 9090
USER app
ENTRYPOINT ["/app/entrypoint-dummy.sh"]

# Default production/local image.
FROM runtime-base
USER app
EXPOSE 8080
ENTRYPOINT ["java","-jar","/app/app.jar"]
