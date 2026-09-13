# ==========================================
# Stage 1: Build stage
# ==========================================
FROM maven:3.9.6-eclipse-temurin-17 AS builder

WORKDIR /build

# Copy pom.xml and download dependencies to leverage Docker layer caching
COPY pom.xml .
RUN mvn dependency:go-offline -B

# Copy source code and package the application (skipping tests for fast build)
COPY src ./src
RUN mvn clean package -DskipTests -B

# Extract jar layers using Spring Boot layertools for optimized runtime container
WORKDIR /build/extracted
RUN java -Djarmode=layertools -jar /build/target/*.jar extract

# ==========================================
# Stage 2: Runtime stage
# Single container running both PostgreSQL and the Spring Boot app
# via supervisord as a process manager
# ==========================================
FROM eclipse-temurin:17-jre-jammy

# Install PostgreSQL 16, supervisord, gosu (safe privilege drop), and curl (for HEALTHCHECK)
RUN apt-get update && \
    apt-get install -y --no-install-recommends \
        curl \
        gosu \
        supervisor \
        gnupg \
        lsb-release && \
    curl -fsSL https://www.postgresql.org/media/keys/ACCC4CF8.asc | gpg --dearmor -o /usr/share/keyrings/postgresql.gpg && \
    echo "deb [signed-by=/usr/share/keyrings/postgresql.gpg] http://apt.postgresql.org/pub/repos/apt $(lsb_release -cs)-pgdg main" > /etc/apt/sources.list.d/pgdg.list && \
    apt-get update && \
    apt-get install -y --no-install-recommends postgresql-16 && \
    rm -rf /var/lib/apt/lists/*

# Create non-root app user/group for the Spring Boot process
RUN groupadd -r appgroup && \
    useradd -r -g appgroup -u 1001 -d /home/appuser -m appuser

# ==========================================
# Environment variables (overridable at docker run time via -e)
# ==========================================
ENV DB_HOST=localhost \
    DB_PORT=5432 \
    DB_NAME=demo_db \
    DB_USERNAME=demo_user \
    DB_PASSWORD=demo_password \
    DDL_AUTO=update \
    SHOW_SQL=false \
    PGDATA=/var/lib/postgresql/16/main

# ==========================================
# Spring Boot app setup
# ==========================================
WORKDIR /app

COPY --from=builder --chown=appuser:appgroup /build/extracted/dependencies/ ./
COPY --from=builder --chown=appuser:appgroup /build/extracted/spring-boot-loader/ ./
COPY --from=builder --chown=appuser:appgroup /build/extracted/snapshot-dependencies/ ./
COPY --from=builder --chown=appuser:appgroup /build/extracted/application/ ./

# ==========================================
# Supervisord — manages both postgres and Spring Boot (standalone mode)
# ==========================================
RUN mkdir -p /var/log/supervisor

COPY supervisord.conf /etc/supervisor/conf.d/supervisord.conf

# entrypoint: starts supervisord (standalone) or Spring Boot directly (external DB)
COPY --chown=root:root entrypoint.sh /entrypoint.sh
RUN chmod +x /entrypoint.sh

EXPOSE 8080

# Health check — waits for the Spring Boot app to respond
HEALTHCHECK --interval=15s --timeout=5s --start-period=60s --retries=5 \
  CMD curl -f http://localhost:8080/actuator/health || exit 1

ENTRYPOINT ["/entrypoint.sh"]
