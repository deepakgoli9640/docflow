# notification-service image.
# Build the jar first:  ./mvnw -pl services/notification-service -am package -DskipTests
# The image only packages that jar. Building with Maven on the host avoids a second multi-GB Maven
# cache inside Docker (matters on an 8 GB laptop), and CI builds the jar once for both tests and image.

# --- Stage 1: split the fat jar into layers (dependencies change rarely, app code often) ---
FROM eclipse-temurin:21-jre-alpine AS extract
WORKDIR /build
COPY target/notification-service-*.jar app.jar
RUN java -Djarmode=tools -jar app.jar extract --layers --launcher --destination extracted

# --- Stage 2: runtime ---
FROM eclipse-temurin:21-jre-alpine
RUN addgroup -S hpg && adduser -S -G hpg hpg
WORKDIR /app
COPY --from=extract /build/extracted/dependencies/ ./
COPY --from=extract /build/extracted/spring-boot-loader/ ./
COPY --from=extract /build/extracted/snapshot-dependencies/ ./
COPY --from=extract /build/extracted/application/ ./
# Never run as root inside the container.
USER hpg
EXPOSE 8085
# Small-footprint JVM settings for local containers; see docs/12-deployment-local-dev.md.
ENV JAVA_TOOL_OPTIONS="-XX:+UseSerialGC -XX:MaxRAMPercentage=60 -Xss512k -XX:TieredStopAtLevel=1 -Duser.timezone=UTC"
ENTRYPOINT ["java", "org.springframework.boot.loader.launch.JarLauncher"]
