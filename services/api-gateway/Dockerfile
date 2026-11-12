# api-gateway image.
# Build the jar first:  ./mvnw -pl services/api-gateway -am package -DskipTests
# See services/merchant-service/Dockerfile for why the jar is built on the host.

FROM eclipse-temurin:21-jre-alpine AS extract
WORKDIR /build
COPY target/api-gateway-*.jar app.jar
RUN java -Djarmode=tools -jar app.jar extract --layers --launcher --destination extracted

FROM eclipse-temurin:21-jre-alpine
RUN addgroup -S hpg && adduser -S -G hpg hpg
WORKDIR /app
COPY --from=extract /build/extracted/dependencies/ ./
COPY --from=extract /build/extracted/spring-boot-loader/ ./
COPY --from=extract /build/extracted/snapshot-dependencies/ ./
COPY --from=extract /build/extracted/application/ ./
USER hpg
# 8080: public API. 9080: actuator (health, metrics) - internal only, never published to the internet.
EXPOSE 8080 9080
ENV JAVA_TOOL_OPTIONS="-XX:+UseSerialGC -XX:MaxRAMPercentage=60 -Xss512k -XX:TieredStopAtLevel=1 -Duser.timezone=UTC"
ENTRYPOINT ["java", "org.springframework.boot.loader.launch.JarLauncher"]
