FROM eclipse-temurin:17-jre-jammy
ARG SOURCE_REVISION=unknown
LABEL org.opencontainers.image.source="https://github.com/Danditon/Backend" \
      org.opencontainers.image.revision="${SOURCE_REVISION}"
RUN groupadd --gid 10001 app \
    && useradd --uid 10001 --gid app --no-create-home app \
    && install -d --owner=10001 --group=10001 /app/uploads
WORKDIR /app
COPY --chown=10001:10001 build/libs/*-SNAPSHOT.jar /app/app.jar
USER 10001:10001
EXPOSE 8080
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=60", "-jar", "/app/app.jar"]
