import is.sudo.jenkins.Utils

def version = ""
String debfiles = null

def get_version_poetry() {
    return sh(
        script: "poetry version | cut -d' ' -f2",
        returnStdout: true
    ).trim()
}



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
            stage('version') {
                steps {
                    script {
                        def poetry_version = get_version_poetry()
                        echo poetry_version
                        sh "env"
                        if (env.TAG_NAME) {
                            if (env.TAG_NAME[0] != "v") {
                                error("invalid tag name: ${env.TAG_NAME}")
                            }
                            if (poetry_version != env.TAG_NAME.substring(1)) {
                                error("tag '${env.TAG_NAME}' does not match ${poetry_version}")
                            }
                            version = env.TAG_NAME
                        }
                        else {
                            version = "${poetry_version}-${env.BUILD_NUMBER}"
                        }
                        currentBuild.displayName += "- ${version}"
                        echo "version ${version}"
                    }
                }
            }

            stage('build container') {
                steps {
                    sh "docker build --pull -t ${DOCKER_NAME}:${DOCKER_TAG} ."
                }

            }

            stage('get package') {
                steps {
                    sh "docker build --pull --target builder -t ${DOCKER_NAME}:builder ."
                    sh "docker container create --name ${REPO}_builder ${DOCKER_NAME}:builder"
                    sh "docker container cp ${REPO}_builder:/sudois/dist ."
                    script {
                        sh 'ls -1 dist/'
                        debfiles = findFiles(glob: 'dist/*.deb')
                    }
                }
            }

            stage('deb file') {
                when {
                    branch "master"
                    expression {
                        debfiles != null && debfiles.size() == 1
                    }
                }
                steps {
                    // there will only be one file
                    script {
                        build(
                            job: "/utils/apt",
                            wait: false,
                            parameters: [[
                                $class: 'StringParameterValue',
                                name: 'filename',
                                value: "${debfiles[0]}"
                            ]]
                        )
                    }
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

            stage('dockerhub push') {
                when {
                    branch "master"
                    expression { config.docker == true }
                }
                steps {
                    sh "docker push ${DOCKER_NAME}:${DOCKER_TAG}"
                    sh "docker tag ${DOCKER_NAME}:${DOCKER_TAG} ${DOCKER_NAME}:v${version}"
                    sh "docker push ${DOCKER_NAME}:v${version}"
                }
            }

        }

        post {
            success {
                archiveArtifacts(
                    artifacts: 'dist/*.tar.gz,dist/*.whl,*.deb',
                    fingerprint: true
                )
                sh "cp dist/*.tar.gz ${env.JENKINS_HOME}/artifacts"
                sh "cp dist/*.whl ${env.JENKINS_HOME}/artifacts"


            }
            cleanup {
                sh "docker container rm ${REPO}_builder"
                //sh "docker rmi ${DOCKER_NAME}:builder"
                cleanWs(deleteDirs: true,
                        disableDeferredWipeout: true,
                        notFailBuild: true)
            }
        }
    }
}
