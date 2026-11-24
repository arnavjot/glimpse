# Build stage: compile and package the Spring Boot jar.
FROM gradle:8.14.3-jdk17 AS build
WORKDIR /src
COPY settings.gradle build.gradle ./
RUN gradle --no-daemon dependencies > /dev/null 2>&1 || true
COPY src ./src
RUN gradle --no-daemon bootJar -x test

# Run stage: just a JRE and the jar, as a non-root user.
FROM eclipse-temurin:17-jre
# ffmpeg/ffprobe for the upload worker (same image runs as API or as worker).
RUN apt-get update     && apt-get install -y --no-install-recommends ffmpeg     && rm -rf /var/lib/apt/lists/*
RUN useradd --create-home glimpse
WORKDIR /srv
COPY --from=build /src/build/libs/glimpse-server.jar app.jar
USER glimpse
EXPOSE 8000
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "/srv/app.jar"]
