# syntax=docker/dockerfile:1
#
# Container image for the public Spyglass demo (spyglass-demo) on Fly.io.
# Multi-stage: the build stage packages the runnable demo jar from source; the
# runtime stage ships only a JRE + that jar. No front-end build is involved —
# spyglass-core serves committed, pre-built static assets.

# --- build stage: compile the reactor and package the demo ---
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /src
COPY . .
# -am builds spyglass-demo's reactor dependencies (spyglass-spring-webmvc ->
# -spring-core -> -core, plus -spring-test-support). Tests are skipped here;
# they run in CI / the release gate, not in the image build.
RUN mvn -B -ntp -pl spyglass-demo -am -DskipTests package

# --- runtime stage: JRE + the exec (fat) jar ---
FROM eclipse-temurin:21-jre
WORKDIR /app
# Wildcard so the copy survives the project version bump (…-1.0.0-exec.jar etc.).
# The plain jar is the default artifact; the runnable one carries the "exec" classifier.
COPY --from=build /src/spyglass-demo/target/spyglass-demo-*-exec.jar /app/app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
