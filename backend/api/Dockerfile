# Local demo image. Maven verify runs outside this build because Testcontainers
# cannot use the Docker socket from inside the image build.
FROM eclipse-temurin:21-jdk-jammy@sha256:e0c60c487345d1dc9d0fc7b6f0496f3cc941e5132e09296cc17a6decc71b902b AS build
RUN apt-get update && apt-get install -y --no-install-recommends unzip && rm -rf /var/lib/apt/lists/*
WORKDIR /src
COPY .mvn .mvn
COPY mvnw pom.xml ./
COPY backend backend
RUN --mount=type=cache,id=streamforge-m2-api,target=/root/.m2 ./mvnw -B -ntp -pl backend/api -am package -DskipTests

FROM eclipse-temurin:21-jre-jammy@sha256:f04fb34e053148344e83317976114ec3f37e4b830ec8bdab5a2fe3cecd7d010b
RUN apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/* \
    && groupadd --system --gid 10001 streamforge \
    && useradd --system --uid 10001 --gid streamforge --home-dir /app --shell /usr/sbin/nologin streamforge
WORKDIR /app
COPY --from=build /src/backend/api/target/streamforge-api-0.1.0-SNAPSHOT.jar /app/api.jar
USER streamforge
EXPOSE 8080
HEALTHCHECK --interval=5s --timeout=5s --start-period=25s --retries=20 \
    CMD curl -fsS http://127.0.0.1:8080/actuator/health || exit 1
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "/app/api.jar"]
