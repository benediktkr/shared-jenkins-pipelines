import is.sudo.jenkins.Utils

def call(Map config) {

    String repo = "${env.JOB_NAME.split('/')[1]}"
    Boolean push_git_tag = Utils.default_or_value(config.push_git_tag, false)
    String docker_image_name = Utils.docker_image_name(repo, config.dockreg)
    String docker_tag = Utils.default_or_value(config.tag, "latest")

    def version = ""
    def debfiles = []


    pipeline {
        agent any

        options {
            timestamps()
            ansiColor("xterm")
            disableConcurrentBuilds()
        }

        environment {
            REPO="${repo}"
        }
        stages {
            stage('version') {
                steps {
                    script {
                        sh "env"
                        sh "git fetch --tags"

                        // # check if file is dirty:
                        // $ git --no-pager diff --stat -- ${repo}/__init__.py"
                        //
                        // # check if file has changed since last commit and grep for a change
                        // $ git show HEAD -- pyproject.toml | grep "+version"
                        // +version = "0.3.28"
                        //
                        // # get latest tag:
                        // # get tag of current commit (if any)
                        // $ git describe --tags --exact-match
                        // v0.3.28
                        //
                        // $ git describe --tags --abbrev=0
                        // v0.3.0
                        // $ git describe --tags
                        // v0.3.0-24-g9f525a4

                        def pyproject = readProperties file: 'pyproject.toml'
                        def pyproject_version = pyproject['version'].replaceAll('"', '')
                        echo "pyproject.toml version: ${pyproject_version}"

                        if (env.TAG_NAME != null) {
                            if (!env.TAG_NAME.endsWith(pyproject_version)) {
                                error "mismatch between git tag (${env.TAG_NAME}) and pyproject (${pyproject_version})"
                            }
                            version = pyproject_version
                            echo "build release version: ${version}"
                        }
                        else {
                            def tag_for_version_exists = sh(
                                script: "git rev-parse v${pyproject_version}",
                                returnStatus: true
                            )
                            // if (tag_for_version_exists == 0 ) {
                            //     error "git tag already exists for ${pyproject_version}, please bump the version number"
                            // }
                            version = "${pyproject_version}.dev"
                            echo "building dev version: ${version}"
                        }
                        currentBuild.displayName += " - ${repo} v${version}"
                    }
                }
            }

            stage('build') {
                steps {
                    // build the builder image with $docker_tag (usually 'latest')
                    sh "docker build --pull --target builder -t ${repo}_builder:${docker_tag} ."
                    // tag the image with the version number so we can delete old versions
                    sh "docker tag ${repo}_builder:${docker_tag} ${repo}_builder:${version}"
                    sh "docker container create --name artifacts-${repo}-${env.BUILD_NUMBER} ${repo}_builder:${version}"
                    sh "docker container cp artifacts-${repo}-${env.BUILD_NUMBER}:/opt/${repo}/dist ."
                    script {
                        sh 'ls -1 dist/'
                        debfiles = findFiles(glob: 'dist/*.deb')
                    }
                }

            }

            stage('docker image') {
                steps {
                    // usually 'docker_tag' is 'latest'.
                    sh "docker build --pull -t ${docker_image_name}:${docker_tag} ."
                    sh "docker tag ${docker_image_name}:${docker_tag} ${docker_image_name}:${version}"
                }
            }

             stage('upload deb file') {
                when {
                    expression { debfiles.size() > 0 }
                }
                steps {
                    echo "deb"
                    sh "cp dist/*.deb ${env.JENKINS_HOME}/artifacts"

                    // curently this only expects 1 file.
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

            stage('publish pip package') {
                when {
                    expression { config.pypi == true }
                }
                steps {
                    // --skip-existing: Ignore errors from files already existing in the repository.
                    sh "docker run --rm -v /etc/pypoetry:/etc/pypoetry -e \"POETRY_CONFIG_DIR=/etc/pypoetry\" ${repo}_builder:${docker_tag} publish -r sudois"
                }
            }

            stage('docker push') {
                when {
                    expression { config.docker == true }
                }
                steps {
                    sh "docker push ${docker_image_name}:${docker_tag}"
                    sh "docker push ${docker_image_name}:${version}"
                    echo "docker push"
                }
            }
        }

        post {
            success {
                archiveArtifacts(
                    artifacts: 'dist/*.tar.gz,dist/*.whl,dist/*.deb',
                    fingerprint: true
                )
                sh "cp -v dist/*.tar.gz ${env.JENKINS_HOME}/artifacts"
                sh "cp -v dist/*.whl ${env.JENKINS_HOME}/artifacts"


            }
            cleanup {
                sh "rm -v dist/*.tar.gz || true"
                sh "rm -v dist/*.whl || true"

                sh "docker container rm artifacts-${repo}-${env.BUILD_NUMBER} || true"
                // keep the ${docker_tag} (usually 'latest' tagged image for faster rebuilding
                // but dont leave the versioned tags hanging around
                sh "docker rmi ${repo}_builder:${version} || true"

                cleanWs(deleteDirs: true,
                        disableDeferredWipeout: true,
                        notFailBuild: true)
            }
            failure {
                emailext body: "${env.BUILD_URL}",
                    subject: "failed: '${env.JOB_NAME} [${env.BUILD_NUMBER}]'",
                    to: "jenkins@sudo.is"
            }
        }
    }
}
