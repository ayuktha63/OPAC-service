# Multi-stage build — compiles with Maven, runs on a slim JRE.
FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /build
COPY pom.xml .
RUN mvn -q dependency:go-offline
COPY src ./src
COPY schemas ./schemas
RUN mvn -q clean package -DskipTests

FROM eclipse-temurin:17-jre-alpine
WORKDIR /app
RUN addgroup -S orque && adduser -S orque -G orque
COPY --from=build /build/target/*.jar app.jar
COPY --from=build /build/schemas ./schemas
RUN mkdir -p /app/logs && chown -R orque:orque /app
USER orque
EXPOSE 8082
ENTRYPOINT ["java", "-Xms256m", "-Xmx512m", "-XX:+UseG1GC", "-jar", "app.jar"]
