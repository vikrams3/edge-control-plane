# Build stage: compiles and tests the control plane, so you only need Docker installed
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /src
COPY pom.xml .
RUN mvn -q -B dependency:go-offline
COPY src ./src
RUN mvn -q -B package

# Run stage
FROM eclipse-temurin:21-jre
COPY --from=build /src/target/edge-control-plane.jar /app/edge-control-plane.jar
EXPOSE 8080 18000
ENTRYPOINT ["java", "-jar", "/app/edge-control-plane.jar"]
