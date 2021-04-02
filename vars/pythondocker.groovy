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
                        sh "env"

                        sh "git fetch --tags"

                        def poetry_version = get_version_poetry()
                        sh "sed -i \"s/__version__.*/__version__ = '${poetry_version}'/g\" ${env.REPO}/__init__.py"

                        def dirty = sh(
                            script: "git --no-pager diff --stat -- ${env.REPO}/__init__.py",
                            returnStdout: true
                        ).trim()
                        def newversion = sh(
                            script: 'git show HEAD -- pyproject.toml | grep "+version"',
                            returnStatus: true
                        )
                        def istagged = sh(
                            script: "git describe --tags --exact-match",
                            returnStatus: true
                        )
                        // if (dirty != "") {
                        //     sh "git add ${env.REPO}/__init__.py"
                        //     sh "git commit -m 'bump __init__.py version to ${poetry_version}'"
                        //     sh "git push origin HEAD:${env.BRANCH_NAME}"

                        // }
                        if (newversion == 0) {
                            if (istagged != 0) {
                                sh "git tag v${poetry_version}"
                                sh "git push --tags"
                            }
                            version = poetry_version
                        }
                        else {
                            version = "${poetry_version}-dev"
                        }


                        // else if (poetry_version != git_version.substring(1)) {
                        //     error("tag '${git_version}' does not match ${poetry_version}")
                        // }

                        currentBuild.displayName += " - v${version}"
                        echo "version ${version}"
                    }
                }
            }

            stage('build container') {
                steps {
                    sh "docker build --pull --build-arg VERSION=${version} -t ${DOCKER_NAME}:${DOCKER_TAG} ."
                }

            }

            stage('get package') {
                steps {
                    sh "docker build --pull --build-arg VERSION=${version} --target builder -t ${DOCKER_NAME}:builder ."
                    sh "docker container create --name ${REPO}_builder ${DOCKER_NAME}:builder"
                    sh "docker container cp ${REPO}_builder:/sudois/dist ."
                    script {
                        sh 'ls -1 dist/'
                        debfiles = findFiles(glob: 'dist/*.deb')
                    }
                }
            }

            stage('upload deb file') {
                when {
                    // expression {
                    //     env.BRANCH_NAME == "master" || env.BRANCH_NAME.startsWith("v")
                    // }
                    expression {
                        debfiles != null && debfiles.size() == 1
                    }
                    expression {
                        config.add_to_apt == true
                    }
                }
                steps {
                    sh "cp dist/*.deb ${env.JENKINS_HOME}/artifacts"

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
                    // branch "master"
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
                    artifacts: 'dist/*.tar.gz,dist/*.whl,dist/*.deb',
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
