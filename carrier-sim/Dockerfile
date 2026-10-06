# Compile with a JDK, run on a slim JRE. No third-party dependencies.
FROM eclipse-temurin:21-jdk-alpine AS build
WORKDIR /src
COPY CarrierSim.java .
RUN javac -d /out CarrierSim.java

FROM eclipse-temurin:21-jre-alpine
RUN addgroup -S app && adduser -S app -G app
USER app
WORKDIR /app
COPY --from=build /out /app
ENV PORT=8091
EXPOSE 8091
HEALTHCHECK --interval=5s --timeout=2s --retries=10 CMD wget -qO- http://127.0.0.1:8091/health || exit 1
ENTRYPOINT ["java", "-XX:+UseSerialGC", "-Xmx64m", "CarrierSim"]
