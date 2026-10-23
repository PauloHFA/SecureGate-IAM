# Multi-stage Dockerfile for SecureGate IAM Core
# Stage 1: Build JAR using Eclipse Temurin JDK 21 Alpine
FROM eclipse-temurin:21-jdk-alpine AS builder
WORKDIR /workspace

# Copy build descriptors and wrapper
COPY .mvn/ .mvn/
COPY mvnw pom.xml ./
COPY libs/security-events/pom.xml libs/security-events/pom.xml
COPY iam-core/pom.xml iam-core/pom.xml
COPY policy-enforcer/pom.xml policy-enforcer/pom.xml
COPY integration-tests/pom.xml integration-tests/pom.xml

# Pre-fetch dependencies
RUN ./mvnw dependency:go-offline -B -ntp || true

# Copy source tree and build iam-core artifact
COPY libs/security-events/ libs/security-events/
COPY infra/ infra/
COPY iam-core/ iam-core/
RUN ./mvnw clean package -pl :iam-core -am -DskipTests -B -ntp

# Stage 2: Minimal, non-root runtime image
FROM eclipse-temurin:21-jre-alpine AS runtime

RUN apk add --no-cache curl tzdata && \
    addgroup -g 10001 -S securegate && \
    adduser -u 10001 -S -G securegate -h /home/securegate securegate

WORKDIR /app

COPY --from=builder --chown=securegate:securegate /workspace/iam-core/target/iam-core-*.jar /app/app.jar

USER 10001:10001

EXPOSE 8080

HEALTHCHECK --interval=10s --timeout=3s --retries=6 --start-period=25s \
  CMD curl -fsS http://127.0.0.1:8080/actuator/health/readiness || exit 1

ENTRYPOINT ["java", "-XX:+UseContainerSupport", "-XX:MaxRAMPercentage=75.0", "-jar", "/app/app.jar"]
