# Multi-stage Dockerfile for SecureGate API Gateway
# Stage 1: Build JAR using Eclipse Temurin JDK 21 Alpine
FROM eclipse-temurin:21-jdk-alpine AS builder
WORKDIR /workspace

COPY .mvn/ .mvn/
COPY mvnw pom.xml ./
COPY libs/security-events/pom.xml libs/security-events/pom.xml
COPY iam-core/pom.xml iam-core/pom.xml
COPY policy-enforcer/pom.xml policy-enforcer/pom.xml
COPY api-gateway/pom.xml api-gateway/pom.xml
COPY integration-tests/pom.xml integration-tests/pom.xml

RUN ./mvnw dependency:go-offline -B -ntp || true

COPY libs/security-events/ libs/security-events/
COPY api-gateway/ api-gateway/
RUN ./mvnw clean package -pl :api-gateway -am -DskipTests -B -ntp

# Stage 2: Minimal, non-root runtime image
FROM eclipse-temurin:21-jre-alpine AS runtime

RUN apk add --no-cache curl tzdata && \
    addgroup -g 10001 -S securegate && \
    adduser -u 10001 -S -G securegate -h /home/securegate securegate

WORKDIR /app

COPY --from=builder --chown=securegate:securegate /workspace/api-gateway/target/api-gateway-*.jar /app/app.jar

USER 10001:10001

EXPOSE 8082

HEALTHCHECK --interval=10s --timeout=3s --start-period=20s --retries=3 \
  CMD curl -f http://localhost:8082/actuator/health/readiness || exit 1

ENTRYPOINT ["java", "-XX:+UseContainerSupport", "-XX:MaxRAMPercentage=75.0", "-jar", "/app/app.jar"]

