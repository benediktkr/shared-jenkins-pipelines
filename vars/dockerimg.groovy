import is.sudo.jenkins.Utils

def call(Map config) {

    String repo = "${env.JOB_NAME.split('/')[1]}"
    String crontab = Utils.default_or_value(config.cron, "")

    pipeline {
        agent any

        triggers {
            cron crontab
        }
        options {
            timestamps()
            ansiColor("xterm")
            disableConcurrentBuilds()
        }

        environment {
            DOCKER_NAME=Utils.docker_image_name(repo)
            DOCKER_TAG=Utils.default_or_value(config.tag, "latest")
        }
        stages {

            stage('build') {
                steps {
                    sh "docker build -t ${DOCKER_NAME}:${DOCKER_TAG} ."
                }
            }

            stage('push') {
                steps {
                    sh "docker push ${DOCKER_NAME}:${DOCKER_TAG}"
                }
            }

            stage('push version tag') {
                when {
                    tag "v*"
                }
                steps {
                    sh "docker tag ${DOCKER_NAME}:${DOCKER_TAG} ${DOCKER_NAME}:${TAG_NAME}"
                    sh "docker push ${DOCKER_NAME}:${TAG_NAME}"
                }
            }

        }
    }
}
