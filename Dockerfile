# The explicit `-noble` suffix keeps Ubuntu 24.04: the suffix-less tags have moved to Ubuntu 26.04.
# Dependabot (package-ecosystem "docker") proposes newer Java 21 tags and digests.
FROM eclipse-temurin:21.0.12.1_1-jre-noble@sha256:000fd431958bc81a24abe1e8e5f0f0fd3ae365a594bd50aadb20696805f9408c

WORKDIR /app

# The pinned base predates the Ubuntu fixes for CVE-2026-95512 and CVE-2026-46675.
# Update those libraries along with the healthcheck's curl dependency.
RUN apt-get update \
    && apt-get install -y --no-install-recommends curl libfreetype6 libpng16-16t64 \
    && rm -rf /var/lib/apt/lists/*

RUN addgroup --system app && adduser --system --ingroup app app

ARG JAR_FILE=build/libs/*.jar
COPY ${JAR_FILE} app.jar

USER app
EXPOSE 8080

HEALTHCHECK --interval=30s --timeout=3s --start-period=30s --retries=3 \
  CMD curl -fsS http://localhost:8080/actuator/health/liveness || exit 1

ENTRYPOINT ["java", "-jar", "/app/app.jar"]
