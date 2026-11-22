pipeline {
    agent any

    options {
        ansiColor('xterm')
    }

    // Сборка по пушу в main: Jenkins сам опрашивает Gitea раз в ~2 минуты. Включается после первой
    // сборки с этим Jenkinsfile. Вебхук Gitea на /git/notifyCommit (с токеном) тоже сработает — ему
    // нужен именно этот триггер.
    triggers {
        pollSCM('H/2 * * * *')
    }

    parameters {
        booleanParam(name: 'APK', defaultValue: false, description: 'Собрать и выложить APK, даже если его код не менялся')
    }

    environment {
        REGISTRY = "localhost:5000/mess9"
        IMAGE_TAG = "${env.BUILD_NUMBER}"
        // Релизы APK — в Gitea этого репозитория, по LAN; Obtainium читает их снаружи
        GITEA = "http://192.168.11.222:3001"
    }

    stages {
        stage('Checkout') {
            steps {
                checkout scm
            }
        }

        stage('Unit tests') {
            steps {
                // Тесты сервера (@QuarkusTest) требуют Postgres через Docker — пока только локально
                sh 'docker build --target test .'
            }
        }

        stage('Build image') {
            steps {
                sh 'docker build --build-arg TRAIN_COMMIT="$(git rev-parse --short HEAD)" --build-arg TRAIN_BUILD="$BUILD_NUMBER" -t $REGISTRY/train-server:$IMAGE_TAG -t $REGISTRY/train-server:latest .'
            }
        }

        stage('Push image') {
            steps {
                sh '''
                    docker push $REGISTRY/train-server:$IMAGE_TAG
                    docker push $REGISTRY/train-server:latest
                '''
            }
        }

        stage('Check storage') {
            steps {
                // Датасет и папка фото создаются руками от mess9. Без диска chattr +i не даст их создать —
                // деплой остановится здесь, а не поднимет сервер без упражнений или с фото не на том диске.
                // --mount, а не -v: -v молча создал бы пустую папку от root
                sh '''
                    docker run --rm --network none --user 1000:1000 --entrypoint sh \
                      -v /mnt/safe_storage/data/train/exercises-dataset:/dataset:ro \
                      --mount type=bind,source=/mnt/safe_storage/data/train/photos,target=/photos \
                      $REGISTRY/train-server:$IMAGE_TAG \
                      -c 'for p in data/exercises.json images videos; do
                            [ -e "/dataset/$p" ] || { echo "нет в датасете: $p"; exit 1; }
                          done
                          [ -w /photos ] || { echo "папка фото не для записи пользователю 1000"; exit 1; }'
                '''
            }
        }

        stage('Deploy') {
            steps {
                // compose запускается прямо из workspace: пути в compose.yaml — абсолютные пути хоста,
                // секреты живут только в credential
                withCredentials([file(credentialsId: 'train-env', variable: 'TRAIN_ENV')]) {
                    sh 'docker compose -f compose.yaml --env-file "$TRAIN_ENV" up -d --remove-orphans'
                }
            }
        }

        stage('Smoke test') {
            steps {
                sh '''
                    # все контейнеры running и healthy, без перезапусков 30 с подряд
                    stable=0
                    for i in $(seq 1 36); do
                      ids=$(docker ps -aq --filter label=com.docker.compose.project=train)
                      bad=$(docker inspect -f '{{.Name}} {{.State.Status}} {{.RestartCount}} {{if .State.Health}}{{.State.Health.Status}}{{end}}' $ids \
                        | awk '$2 != "running" || $3 != "0" || ($4 != "" && $4 != "healthy")')
                      if [ -z "$bad" ]; then stable=$((stable + 1)); else stable=0; fi
                      if [ $stable -ge 6 ]; then
                        echo "Smoke test passed"
                        exit 0
                      fi
                      sleep 5
                    done
                    echo "Smoke test failed:"
                    echo "$bad"
                    # не docker compose logs: он прочитал бы compose.yaml и упал без секретов
                    for id in $ids; do
                      echo "--- $(docker inspect -f '{{.Name}}' $id)"
                      docker logs --tail 40 $id 2>&1 || true
                    done
                    exit 1
                '''
            }
        }

        // APK — после деплоя, чтобы его не задерживать, и только если менялся его код (или параметр
        // APK): иначе каждый пуш сервера давал бы релиз без изменений и обновление в Obtainium.
        // Тег релиза = versionName (0.3.0+<коммит>) — по нему Obtainium сверяет установленную версию
        stage('APK') {
            when {
                anyOf {
                    changeset 'app/**'
                    changeset 'android/**'
                    changeset 'client/**'
                    changeset 'core/**'
                    changeset 'gradle/**'
                    changeset 'gradle.properties'
                    expression { params.APK }
                }
            }
            steps {
                withCredentials([
                    file(credentialsId: 'train-apk-keystore', variable: 'KEYSTORE'),
                    string(credentialsId: 'train-apk-password', variable: 'STORE_PASS'),
                    string(credentialsId: 'gitea-release-token', variable: 'GITEA_TOKEN'),
                ]) {
                    sh '''
                        set -eu
                        # versionCode — число коммитов: в неполном клоне оно меньше прежнего, и APK не встанет поверх
                        [ "$(git rev-parse --is-shallow-repository)" = false ] || git fetch --unshallow
                        CODE=$(git rev-list --count HEAD)
                        COMMIT=$(git rev-parse --short=7 HEAD)
                        VERSION="$(sed -n 's/^version=//p' gradle.properties)+$COMMIT"

                        rm -rf build/apk
                        docker build --target apk-out --output type=local,dest=build/apk \
                          --build-arg TRAIN_COMMIT="$COMMIT" --build-arg TRAIN_VERSION_CODE="$CODE" \
                          --secret id=keystore,src="$KEYSTORE" --secret id=storepass,env=STORE_PASS .
                        APK=build/apk/android-release.apk
                        [ -f "$APK" ] || { echo "Нет подписанного APK:"; ls -l build/apk; exit 1; }

                        API="$GITEA/api/v1/repos/mess9/train/releases"
                        status=$(curl -s -o release.json -w '%{http_code}' -X POST "$API" \
                          -H "Authorization: token $GITEA_TOKEN" -H 'Content-Type: application/json' \
                          -d "$(printf '{"tag_name":"%s","name":"%s","target_commitish":"%s"}' "$VERSION" "$VERSION" "$(git rev-parse HEAD)")")
                        if [ "$status" = 409 ]; then
                          echo "Релиз $VERSION уже есть — не выкладываю"
                          exit 0
                        fi
                        [ "$status" = 201 ] || { echo "Gitea: $status"; cat release.json; exit 1; }
                        # Первый id в ответе — сам релиз (автор и файлы идут после)
                        ID=$(grep -o '"id":[0-9]*' release.json | head -1 | cut -d: -f2)
                        curl -sf -o /dev/null -X POST "$API/$ID/assets?name=train-$(echo "$VERSION" | tr + -).apk" \
                          -H "Authorization: token $GITEA_TOKEN" \
                          -F "attachment=@$APK;type=application/vnd.android.package-archive" || {
                            # Иначе повтор упёрся бы в 409 и оставил релиз без APK
                            curl -s -o /dev/null -X DELETE "$API/$ID" -H "Authorization: token $GITEA_TOKEN"
                            curl -s -o /dev/null -X DELETE "$GITEA/api/v1/repos/mess9/train/tags/$VERSION" -H "Authorization: token $GITEA_TOKEN"
                            echo "APK не загрузился, релиз $VERSION удалён"
                            exit 1
                          }
                        echo "Выложен релиз $VERSION (versionCode $CODE)"
                    '''
                }
            }
        }
    }

    post {
        success {
            sh '''
                curl -X POST "http://n8n:5678/webhook/jenkins-deploy" \
                  -H "Content-Type: application/json" \
                  -d "{\\"project\\":\\"train\\",\\"branch\\":\\"$BRANCH_NAME\\",\\"imageTag\\":\\"$IMAGE_TAG\\",\\"status\\":\\"deployed\\",\\"url\\":\\"https://train.fil-lost.org\\"}"
            '''
        }

        failure {
            sh '''
                curl -X POST "http://n8n:5678/webhook/jenkins-deploy" \
                  -H "Content-Type: application/json" \
                  -d '{"project":"train","status":"failure"}'
            '''
        }
    }
}
