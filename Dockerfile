# Build stage: compiles the jar. Tests run in CI, not here.
FROM eclipse-temurin:17-jdk AS build
WORKDIR /app
COPY gradlew settings.gradle build.gradle ./
COPY gradle gradle
COPY src src
RUN sh ./gradlew bootJar -x test --no-daemon

# Run stage: the JRE and the jar only, as a user without privileges.
FROM eclipse-temurin:17-jre
RUN useradd --system --uid 10001 app && mkdir -p /app/private_uploads && chown -R app /app
WORKDIR /app
COPY --from=build /app/build/libs/*.jar app.jar
USER app
EXPOSE 8080
# The heap follows the container's memory limit instead of a fixed number.
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "app.jar"]
