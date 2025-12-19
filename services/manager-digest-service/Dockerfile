# Stage 1: Build the application
FROM sbtscala/scala-sbt:eclipse-temurin-jammy-11.0.22_7_1.9.9_2.13.13 AS builder
WORKDIR /build

# Copy build definition files first (for better caching)
COPY build.sbt ./
COPY project ./project/

# Download dependencies (this layer gets cached if build.sbt doesn't change)
RUN sbt update

# Copy source code
COPY src ./src/

# Compile and create runtime distribution
RUN sbt compile stage

# Stage 2: Runtime image (much smaller)
FROM eclipse-temurin:11-jre-jammy
WORKDIR /app

# Copy only the built application from builder stage
COPY --from=builder /build/target/universal/stage ./

ENTRYPOINT ["./bin/manager-digest-service"]

