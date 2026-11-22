# Образ server: API + PWA одним процессом. Сборка сама собирает app (wasm) и кладёт его в ресурсы.
# Тесты сервера здесь не идут: @QuarkusTest нужен Docker для Postgres.

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

# docker build --target test . — тесты без Postgres: core, client, cli
FROM build AS test
RUN --mount=type=cache,target=/root/.gradle \
    sh gradlew --no-daemon :core:jvmTest :client:jvmTest :cli:test

# APK (docs/PLAN-android.md, этап 4) — в образ сервера не входит, Jenkins собирает его отдельно:
# docker build --target apk-out --output type=local,dest=build/apk .
# Без секретов keystore и storepass — неподписанный android-release-unsigned.apk (поставить нельзя).
# SDK — своей стадией, в кэше слоёв; версии — как compileSdk и build-tools в android/
FROM eclipse-temurin:21-jdk AS android-sdk
RUN apt-get update && apt-get install -y --no-install-recommends curl unzip \
    && rm -rf /var/lib/apt/lists/*
ENV ANDROID_HOME=/opt/android-sdk
RUN curl -fsSLo /tmp/tools.zip https://dl.google.com/android/repository/commandlinetools-linux-13114758_latest.zip \
    && mkdir -p $ANDROID_HOME/cmdline-tools \
    && unzip -q /tmp/tools.zip -d $ANDROID_HOME/cmdline-tools \
    && mv $ANDROID_HOME/cmdline-tools/cmdline-tools $ANDROID_HOME/cmdline-tools/latest \
    && rm /tmp/tools.zip \
    && yes | $ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager --licenses > /dev/null \
    && $ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager "platforms;android-37.0" "build-tools;36.0.0" > /dev/null

FROM android-sdk AS apk
WORKDIR /src
# Те же исходники, что у build: settings.gradle.kts включает server и cli, их папки нужны
COPY gradlew settings.gradle.kts build.gradle.kts gradle.properties ./
COPY gradle gradle
COPY kotlin-js-store kotlin-js-store
COPY core core
COPY client client
COPY cli cli
COPY app app
COPY server server
COPY android android
# versionCode (число коммитов) и коммит — от Jenkins: .git в образ не копируется
ARG TRAIN_COMMIT=
ARG TRAIN_VERSION_CODE=
# Ключ и пароль — секретами BuildKit: в слои и историю образа не попадают
RUN --mount=type=cache,target=/root/.gradle \
    --mount=type=secret,id=keystore \
    --mount=type=secret,id=storepass \
    TRAIN_SIGNING_STORE=/run/secrets/keystore \
    TRAIN_SIGNING_STOREPASSWORD="$(cat /run/secrets/storepass 2> /dev/null)" \
    sh gradlew --no-daemon :android:assembleRelease

FROM scratch AS apk-out
COPY --from=apk /src/android/build/outputs/apk/release/ /

FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
# Коммит и номер сборки — только в последней стадии: стадии сборки и тестов остаются в кэше.
# /api/info отдаёт версию 0.2.0+<коммит> (ADR 0009)
ARG TRAIN_COMMIT=dev
ARG TRAIN_BUILD=
ENV TRAIN_COMMIT=$TRAIN_COMMIT TRAIN_BUILD=$TRAIN_BUILD
COPY --from=build /src/server/build/quarkus-app/ ./
ENV PORT=7313 \
    JAVA_OPTS="-XX:MaxRAMPercentage=50 -Djava.util.logging.manager=org.jboss.logmanager.LogManager"
EXPOSE 7313
USER 1000:1000
CMD ["sh", "-c", "exec java $JAVA_OPTS -jar quarkus-run.jar"]
