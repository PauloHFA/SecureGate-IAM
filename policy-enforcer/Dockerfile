# Multi-stage Dockerfile for SecureGate Policy Enforcer
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
COPY infra/ infra/
COPY policy-enforcer/ policy-enforcer/
RUN ./mvnw clean package -pl :policy-enforcer -am -DskipTests -B -ntp

# Stage 2: Minimal, non-root runtime image
FROM eclipse-temurin:21-jre-alpine AS runtime

RUN apk add --no-cache curl tzdata && \
    addgroup -g 10001 -S securegate && \
    adduser -u 10001 -S -G securegate -h /home/securegate securegate

WORKDIR /app

COPY --from=builder --chown=securegate:securegate /workspace/policy-enforcer/target/policy-enforcer-*.jar /app/app.jar

USER 10001:10001

EXPOSE 19091

ENTRYPOINT ["java", "-XX:+UseContainerSupport", "-XX:MaxRAMPercentage=75.0", "-jar", "/app/app.jar"]
