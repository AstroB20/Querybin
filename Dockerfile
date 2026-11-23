# One Dockerfile builds either service; choose which with the MODULE build argument
# (builder-service or runtime-service). docker-compose.yml does this for you.

FROM eclipse-temurin:21-jdk AS build
ARG MODULE
WORKDIR /src
COPY mvnw pom.xml ./
COPY .mvn .mvn
COPY shared shared
COPY builder-service builder-service
COPY runtime-service runtime-service
RUN --mount=type=cache,target=/root/.m2 \
    sed -i 's/\r$//' mvnw && chmod +x mvnw \
    && ./mvnw -B -q -DskipTests -pl "${MODULE}" -am package \
    && cp "${MODULE}"/target/*-SNAPSHOT.jar /app.jar

FROM eclipse-temurin:21-jre
RUN useradd --system --uid 10001 --home-dir /home/app --create-home app \
    && mkdir /data && chown app:app /data
COPY --from=build /app.jar /app/app.jar
USER app
# Inside a container the service listens on all interfaces; compose decides which port is published.
# Nothing is ever created in the target database, and connections to it are read-only. (The builder
# overrides SPRING_SQL_INIT_MODE / the read-only flag as needed; these are safe defaults.)
ENV SERVER_ADDRESS=0.0.0.0 \
    SPRING_SQL_INIT_MODE=never \
    SPRING_DATASOURCE_HIKARI_READ_ONLY=true \
    APP_METADATA_URL=jdbc:h2:file:/data/metadata
VOLUME /data
ENTRYPOINT ["java", "-jar", "/app/app.jar"]