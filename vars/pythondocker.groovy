def call(Map config) {
    pipeline {
        agent any

        options {
            timestamps()
            ansiColor("xterm")
            disableConcurrentBuilds()
        }

        environment {
            NAME="${JOB_NAME.split('/')[1]}"
            DOCKER_TAG="latest"
        }
        stages {

            stage('build docker container') {
                steps {
                    sh "docker build -t benediktkr/${NAME}:${DOCKER_TAG} ."
                }
            }

            stage('build python package') {
                steps {
                    sh "docker run --name ${NAME}_jenkins benediktkr/${NAME}:${DOCKER_TAG} build --ansi"
                    sh "docker cp ${NAME}_jenkins:/sudois/dist ."
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

            stage('dockerhub latest tag') {
                when {
                    not { tag "v*" }
                    branch "master"
                    expression { config.docker == true }
                }
                steps {
                    sh "docker push benediktkr/${NAME}:${DOCKER_TAG}"
                }
            }

            stage('dockerhub version tag') {
                when {
                    tag "v*"
                    expression { config.docker == true }
                }
                steps {
                    sh "docker tag benediktkr/${NAME}:${DOCKER_TAG} benediktkr/${NAME}:${TAG_NAME}"
                    sh "docker push benediktkr/${NAME}:${TAG_NAME}"
                }
            }

        }

        post {
            success {
                archiveArtifacts artifacts: 'dist/*.tar.gz,dist/*.whl', fingerprint: true
            }
            cleanup {
                sh "docker rm ${NAME}_jenkins"
                cleanWs(deleteDirs: true,
                        disableDeferredWipeout: true,
                        notFailBuild: true)
            }
        }
    }
}
