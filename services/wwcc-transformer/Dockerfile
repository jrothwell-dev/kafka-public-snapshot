FROM sbtscala/scala-sbt:eclipse-temurin-jammy-11.0.22_7_1.9.9_2.13.13 AS builder
WORKDIR /build
COPY build.sbt ./
COPY project ./project/
RUN sbt update
COPY src ./src/
RUN sbt compile stage

FROM eclipse-temurin:11-jre-jammy
WORKDIR /app
COPY --from=builder /build/target/universal/stage ./
ENTRYPOINT ["./bin/wwcc-transformer"]