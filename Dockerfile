FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /app
COPY pom.xml .
RUN mvn dependency:go-offline
COPY src ./src
RUN mvn package -DskipTests

FROM eclipse-temurin:17-jre-alpine
WORKDIR /app
COPY --from=build /app/target/waha-0.1.0.jar app.jar
COPY db/migrations /flyway-sql/migrations
COPY db/stored-procs /flyway-sql/stored-procs
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
