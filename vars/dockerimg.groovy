import is.sudo.jenkins.Utils

def call(Map config) {

    String repo = "${env.JOB_NAME.split('/')[1]}"
    String crontab = Utils.default_or_value(config.cron, "")
    String dockreg = Utils.default_or_value(config.dockreg, Utils.dockerhub_account(null))

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
            DOCKER_NAME=Utils.docker_image_name(repo, dockreg)
            DOCKER_TAG=Utils.default_or_value(config.tag, "latest")
        }
        stages {

            stage('build') {
                steps {
                    sh "docker build --pull -t ${DOCKER_NAME}:${DOCKER_TAG} ."
                }
            }

            stage('push') {
                when {
                    expression { config.docker_push != false }

                }
                steps {
                    sh "docker push ${DOCKER_NAME}:${DOCKER_TAG}"
                }
            }

            stage('push version tag') {
                when {
                    tag "v*"
                    expression { config.docker_push != false }

                }
                steps {
                    sh "docker tag ${DOCKER_NAME}:${DOCKER_TAG} ${DOCKER_NAME}:${TAG_NAME}"
                    sh "docker push ${DOCKER_NAME}:${TAG_NAME}"
                }
            }

        }

        post {
            cleanup {
                cleanWs(deleteDirs: true,
                        disableDeferredWipeout: true,
                        notFailBuild: true)
            }
        }

    }
}
