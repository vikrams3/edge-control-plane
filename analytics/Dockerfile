# Build stage: compiles and tests the analytics service, so you only need Docker installed
# Maven runs on the build machine's CPU; only the run stage is built for the target (e.g. amd64 on GKE)
FROM --platform=$BUILDPLATFORM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /src
COPY pom.xml .
RUN mvn -q -B dependency:go-offline
COPY src ./src
RUN mvn -q -B package

# Run stage
FROM eclipse-temurin:21-jre
COPY --from=build /src/target/edge-analytics.jar /app/edge-analytics.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/edge-analytics.jar"]
