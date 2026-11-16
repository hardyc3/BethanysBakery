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

FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
RUN addgroup -S app && adduser -S app -G app
COPY --from=build /workspace/build/libs/*.jar app.jar
USER app
EXPOSE 8080
ENTRYPOINT ["java","-jar","/app/app.jar"]
