# Packages the Worker exec jar, not the plain test jar and not the API jar.
FROM eclipse-temurin:21-jdk-jammy@sha256:e0c60c487345d1dc9d0fc7b6f0496f3cc941e5132e09296cc17a6decc71b902b AS build
RUN apt-get update && apt-get install -y --no-install-recommends unzip && rm -rf /var/lib/apt/lists/*
WORKDIR /src
COPY .mvn .mvn
COPY mvnw pom.xml ./
COPY backend backend
RUN --mount=type=cache,id=streamforge-m2-worker,target=/root/.m2 ./mvnw -B -ntp -pl backend/worker -am package -DskipTests

FROM eclipse-temurin:21-jre-jammy@sha256:f04fb34e053148344e83317976114ec3f37e4b830ec8bdab5a2fe3cecd7d010b
RUN groupadd --system --gid 10001 streamforge \
    && useradd --system --uid 10001 --gid streamforge --home-dir /app --shell /usr/sbin/nologin streamforge
WORKDIR /app
COPY --from=build /src/backend/worker/target/streamforge-worker-0.1.0-SNAPSHOT-exec.jar /app/worker.jar
USER streamforge
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "/app/worker.jar"]
