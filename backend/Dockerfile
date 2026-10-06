# ---- build ---------------------------------------------------------------------------------
FROM eclipse-temurin:21-jdk AS build
WORKDIR /src
# Resolve dependencies in their own layer so code changes don't re-download the world.
COPY .mvn .mvn
COPY mvnw pom.xml ./
RUN ./mvnw -B -q dependency:go-offline
COPY src src
RUN ./mvnw -B -q -DskipTests package \
 && java -Djarmode=tools -jar target/fulfillops-*.jar extract --layers --launcher --destination /layers

# ---- run -----------------------------------------------------------------------------------
FROM eclipse-temurin:21-jre-alpine
RUN addgroup -S app && adduser -S app -G app && apk add --no-cache wget
USER app
WORKDIR /app
COPY --from=build /layers/dependencies/ ./
COPY --from=build /layers/spring-boot-loader/ ./
COPY --from=build /layers/snapshot-dependencies/ ./
COPY --from=build /layers/application/ ./
ENV SPRING_PROFILES_ACTIVE=prod \
    JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError"
EXPOSE 8080
HEALTHCHECK --interval=10s --timeout=3s --start-period=40s --retries=5 \
  CMD wget -qO- http://127.0.0.1:8080/actuator/health || exit 1
ENTRYPOINT ["java", "org.springframework.boot.loader.launch.JarLauncher"]
