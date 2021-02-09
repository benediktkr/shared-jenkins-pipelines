import is.sudo.jenkins.Utils

def call(Map config) {

    String repo = "${env.JOB_NAME.split('/')[1]}"

    pipeline {
        agent any

        options {
            timestamps()
            ansiColor("xterm")
            disableConcurrentBuilds()
        }

        environment {
            DOCKER_NAME=Utils.docker_image_name(repo)
            DOCKER_TAG=Utils.default_or_value(config.tag, "latest")
            REPO="${repo}"
        }
        stages {
            stage('env') {
                steps {
                    sh "env"
                }
            }

            stage('build') {
                steps {
                    sh "docker build -t ${DOCKER_NAME}:${DOCKER_TAG} ."
                    sh "docker container create --name ${REPO}_builder ${DOCKER_NAME}:${DOCKER_TAG}"
                    sh "docker container cp ${REPO}_builder:/sudois/dist ."
                }
            }

            stage('pypi') {
                when {
                    expression { config.pypi == true }
                }
                steps {
                    sh "echo 'pypi here i come'"
                }
            }

            stage('dockerhub push latest') {
                when {
                    not { tag "v*" }
                    branch "master"
                    expression { config.docker == true }
                }
                steps {
                    sh "docker push ${DOCKER_NAME}:${DOCKER_TAG}"
                }
            }

            stage('dockerhub version tag') {
                when {
                    tag "v*"
                    expression { config.docker == true }
                }
                steps {
                    sh "docker tag ${DOCKER_NAME}:${DOCKER_TAG} ${DOCKER_NAME}:${TAG_NAME}"
                    sh "docker push ${DOCKER_NAME}/${NAME}:${TAG_NAME}"
                }
            }

        }

        post {
            success {
                archiveArtifacts artifacts: 'dist/*.tar.gz,dist/*.whl', fingerprint: true
            }
            cleanup {
                sh "docker container rm ${REPO}_builder"
                cleanWs(deleteDirs: true,
                        disableDeferredWipeout: true,
                        notFailBuild: true)
            }
        }
    }
}
