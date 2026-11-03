# Образ server: API + PWA одним процессом. Сборка сама собирает app (wasm) и кладёт его в ресурсы.
# Тесты здесь не идут: @QuarkusTest нужен Docker для Postgres — их гоняет CI отдельно.

FROM eclipse-temurin:21-jdk AS build
# Node, который Kotlin качает для wasm-сборки, без libatomic не запускается
RUN apt-get update && apt-get install -y --no-install-recommends libatomic1 \
    && rm -rf /var/lib/apt/lists/*
WORKDIR /src
COPY gradlew settings.gradle.kts build.gradle.kts gradle.properties ./
COPY gradle gradle
COPY kotlin-js-store kotlin-js-store
COPY core core
COPY client client
COPY cli cli
COPY app app
COPY server server
# Кэш Gradle (зависимости, Node и binaryen для wasm) переживает пересборки образа
RUN --mount=type=cache,target=/root/.gradle \
    sh gradlew --no-daemon :server:quarkusBuild

FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
COPY --from=build /src/server/build/quarkus-app/ ./
ENV PORT=7313 \
    JAVA_OPTS="-XX:MaxRAMPercentage=50 -Djava.util.logging.manager=org.jboss.logmanager.LogManager"
EXPOSE 7313
USER 1000:1000
CMD ["sh", "-c", "exec java $JAVA_OPTS -jar quarkus-run.jar"]
