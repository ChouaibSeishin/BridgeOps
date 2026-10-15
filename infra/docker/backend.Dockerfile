FROM eclipse-temurin@sha256:e0c60c487345d1dc9d0fc7b6f0496f3cc941e5132e09296cc17a6decc71b902b AS build
RUN apt-get update && apt-get install -y --no-install-recommends unzip && rm -rf /var/lib/apt/lists/*
WORKDIR /workspace
COPY backend/ ./
RUN chmod +x mvnw && ./mvnw -B -ntp -DskipTests package

FROM eclipse-temurin@sha256:f04fb34e053148344e83317976114ec3f37e4b830ec8bdab5a2fe3cecd7d010b
RUN groupadd --system bridgeops && useradd --system --gid bridgeops --home-dir /app bridgeops
WORKDIR /app
COPY --from=build --chown=bridgeops:bridgeops /workspace/target/bridgeops-backend-0.1.0-SNAPSHOT.jar app.jar
USER bridgeops
EXPOSE 8080
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "app.jar"]
