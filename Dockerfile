FROM eclipse-temurin:17-jdk@sha256:fbe9d94debbe24e529acfe235b98f065edea050a2dc00129b7d86261ce3248c8 AS java-build
ARG SERVICE
WORKDIR /workspace
COPY services/${SERVICE}/gradlew services/${SERVICE}/build.gradle* services/${SERVICE}/settings.gradle* ./
COPY services/${SERVICE}/gradle/ gradle/
COPY services/${SERVICE}/src/main/ src/main/
RUN chmod +x gradlew && ./gradlew --no-daemon bootJar

FROM eclipse-temurin:17-jre@sha256:fb2cec8da84e3f086b59e29bf432d372e396fe0c247adcfb89303a28c2e313c0 AS java-app
WORKDIR /app
COPY --from=java-build /workspace/build/libs/*.jar /app/app.jar
USER 10001:10001
ENV JAVA_TOOL_OPTIONS="-Duser.timezone=UTC"
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]

FROM node:22-bookworm-slim AS web-build
WORKDIR /workspace
COPY frontend/package.json frontend/package-lock.json ./
RUN npm ci
COPY frontend/ ./
ENV IDENTITY_API_URL=http://identity:8080 NEXT_STANDALONE=1 NEXT_TELEMETRY_DISABLED=1
RUN npm run build

FROM node:22-bookworm-slim AS web-app
WORKDIR /app
ENV NODE_ENV=production HOSTNAME=0.0.0.0 PORT=3000 NEXT_TELEMETRY_DISABLED=1
COPY --from=web-build --chown=node:node /workspace/.next/standalone/ ./
COPY --from=web-build --chown=node:node /workspace/.next/static/ ./.next/static/
USER node
EXPOSE 3000
CMD ["node", "server.js"]

FROM nginx:stable-alpine AS gateway
COPY Docker/nginx/default.conf.template /etc/nginx/conf.d/default.conf
