# ─────────────────────────────────────────────────────
# Ishin Gateway — Multi-stage Docker Image
# ─────────────────────────────────────────────────────

# Stage 1: Build
FROM maven:3.9.9-eclipse-temurin-21 AS builder
WORKDIR /build
COPY pom.xml .
COPY settings.xml /tmp/settings.xml
RUN mvn -s /tmp/settings.xml dependency:go-offline -U -q || true
COPY src/ src/
COPY rules/ rules/
RUN mvn -s /tmp/settings.xml -DskipTests clean package -U

# Stage 2: Runtime
FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=builder /build/target/ishin-gateway-*.jar app.jar
COPY --from=builder /build/rules/ rules/
EXPOSE 9091 9190 7100 18080
ENTRYPOINT ["java", "-XX:+UseZGC", "-XX:+ZGenerational", "-Xms128m", "-Xmx256m", "-jar", "app.jar"]
