# US-5: Docker deployment
# Multi-stage build for Code Review Bot

# Stage 1: Build
FROM gradle:8.5-jdk21 AS builder

WORKDIR /app

# Copy Gradle configuration
COPY build.gradle settings.gradle ./
COPY gradle gradle
COPY gradlew ./

# Download dependencies (cached layer)
RUN ./gradlew dependencies --no-daemon || true

# Copy source code
COPY src ./src

# Build application
RUN ./gradlew clean build -x test --no-daemon

# Stage 2: Runtime
FROM eclipse-temurin:21-jre-alpine

WORKDIR /app

# Create non-root user for security
RUN addgroup -g 1001 appgroup && \
    adduser -u 1001 -G appgroup -D appuser

# Copy built JAR from builder stage
COPY --from=builder /app/build/libs/*.jar app.jar

# Change ownership
RUN chown -R appuser:appgroup /app

# Switch to non-root user
USER appuser

# Expose port
EXPOSE 8080

# Health check
HEALTHCHECK --interval=30s --timeout=3s --start-period=40s --retries=3 \
    CMD wget --no-verbose --tries=1 --spider http://localhost:8080/actuator/health || exit 1

# Entry point
ENTRYPOINT ["java", "-jar", "app.jar"]

# Default arguments
CMD ["--spring.profiles.active=docker"]
