FROM eclipse-temurin:17-jdk@sha256:fbe9d94debbe24e529acfe235b98f065edea050a2dc00129b7d86261ce3248c8 AS build
WORKDIR /workspace
COPY gradlew settings.gradle.kts build.gradle.kts ./
COPY gradle/ gradle/
RUN chmod +x gradlew && ./gradlew --no-daemon help
COPY src/main/ src/main/
RUN ./gradlew --no-daemon bootJar

FROM eclipse-temurin:17-jre@sha256:fb2cec8da84e3f086b59e29bf432d372e396fe0c247adcfb89303a28c2e313c0
WORKDIR /app
COPY --from=build /workspace/build/libs/assignment-service.jar /app/app.jar
USER 10001:10001
ENV JAVA_TOOL_OPTIONS="-Duser.timezone=UTC"
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
