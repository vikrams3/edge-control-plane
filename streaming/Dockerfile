# Build stage: compiles and tests the streaming backend, so you only need Docker installed
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /src
COPY pom.xml .
RUN mvn -q -B dependency:go-offline
COPY src ./src
RUN mvn -q -B package

# Run stage
FROM eclipse-temurin:21-jre
COPY --from=build /src/target/edge-streaming.jar /app/edge-streaming.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/edge-streaming.jar"]
