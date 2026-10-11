FROM maven:3.9.11-eclipse-temurin-21 AS build
WORKDIR /src
COPY pom.xml .
RUN mvn -q -DskipTests dependency:go-offline
COPY src ./src
RUN mvn -q test package
FROM eclipse-temurin:21-jre
WORKDIR /app
RUN useradd -u 10001 -m appuser && mkdir -p /data/documents && chown appuser:appuser /data/documents
COPY --from=build /src/target/trade-platform-0.1.0.jar /app/app.jar
USER appuser
EXPOSE 8080
ENTRYPOINT ["java","-XX:MaxRAMPercentage=75","-jar","/app/app.jar"]
