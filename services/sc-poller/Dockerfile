FROM hseeberger/scala-sbt:11.0.18_1.9.0_2.13.11 AS builder
WORKDIR /build
COPY . .
RUN sbt clean assembly

FROM openjdk:11-jre-slim
WORKDIR /app
COPY --from=builder /build/target/scala-2.13/*.jar app.jar
ENTRYPOINT ["java", "-jar", "app.jar"]