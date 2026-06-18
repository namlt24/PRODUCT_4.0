# ---- Stage 1: build ----
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /workspace

# Copy the whole reactor so the parent pom's module list resolves, then build only
# this service (+ its common-lib dependency) via -pl/-am.
COPY . .
RUN mvn -q -pl common-lib,api-gateway -am clean package -DskipTests

# ---- Stage 2: runtime (JRE only, smaller image) ----
FROM eclipse-temurin:21-jre-alpine
WORKDIR /app

# Run as non-root
RUN addgroup -S app && adduser -S app -G app
COPY --from=build /workspace/api-gateway/target/api-gateway.jar app.jar
USER app

EXPOSE 8080
ENV JAVA_OPTS="-XX:MaxRAMPercentage=75 -XX:+UseG1GC -XX:+ExitOnOutOfMemoryError"

HEALTHCHECK --interval=15s --timeout=3s --retries=4 \
  CMD wget -qO- http://localhost:8080/actuator/health/readiness || exit 1

ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar app.jar"]
