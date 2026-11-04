pipeline {
    agent any

    options {
        ansiColor('xterm')
    }

    environment {
        REGISTRY = "localhost:5000/mess9"
        IMAGE_TAG = "${env.BUILD_NUMBER}"
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
