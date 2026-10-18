FROM gradle:9.5.1-jdk21 AS build
WORKDIR /workspace
COPY build.gradle.kts settings.gradle.kts ./
COPY gradle ./gradle
# Warm the dependency cache in its own layer.
RUN gradle dependencies --no-daemon -q > /dev/null 2>&1 || true
COPY src ./src
RUN gradle bootJar -x test --no-daemon -q \
    && cp build/libs/*-SNAPSHOT.jar app.jar

FROM eclipse-temurin:21-jre
# /data/receipts is the mount point of the receipts volume; a new named volume takes the owner of this directory
RUN useradd --system --uid 1001 app && mkdir -p /data/receipts && chown app /data/receipts
USER app
WORKDIR /app
COPY --from=build /workspace/app.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
