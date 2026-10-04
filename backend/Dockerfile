# Build context: repository root (needs ../database for Flyway migrations).
FROM eclipse-temurin:21-jdk AS build
WORKDIR /src/backend
COPY backend/gradlew backend/settings.gradle.kts backend/build.gradle.kts ./
COPY backend/gradle ./gradle
RUN ./gradlew --no-daemon -q dependencies > /dev/null
COPY database /src/database
COPY backend/src ./src
RUN ./gradlew --no-daemon -q bootJar

FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
COPY --from=build /src/backend/build/libs/siren-backend-*.jar app.jar
EXPOSE 8081
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "/app/app.jar"]
