import is.sudo.jenkins.Utils

def call(Map config) {

    String repo = "${env.JOB_NAME.split('/')[1]}"
    String docker_image_name = Utils.docker_image_name(repo, config.dockreg)
    Boolean pip_publish_tags_only = Utils.default_or_value(config.pip_publish_tags_only, true)

    def new_version_commit = false
    def version = ""
    def artifacts_exist = false
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

                        new_version_commit = sh(
                            script: "git show HEAD -- pyproject.toml | grep '+version'",
                            returnStatus: true) == 0
                        echo "commit has new version: ${new_version_commit}"

                        def pyproject = readProperties file: 'pyproject.toml'
                        def pyproject_version = pyproject['version'].replaceAll('"', '')
                        echo "pyproject.toml version: ${pyproject_version}"

                        version = pyproject_version

                        // both findFiles and fileExists are restricted to the workspace,
                        // but oth this wont work if we have more build agents
                        // '...${version}.tar.gz' would also work
                        artifacts_exist = sh(
                            script: "ls ../../artifacts/${repo}-${version}\\.*",
                            returnStatus: true) == 0
                        echo "found existing artifacts for version ${version}: ${artifacts_exist}"

                        def tag_for_version_exists = sh(
                            script: "git rev-parse v${pyproject_version}",
                            returnStatus: true) == 0
                        echo "version tag exists: ${tag_for_version_exists}"

                        currentBuild.displayName += " - ${repo} v${version}"
                        currentBuild.description = [
                            "commit has new version: ${new_version_commit}",
                            "artifacts exist: ${artifacts_exist}",
                            "version tag exists: ${tag_for_version_exists}"
                        ].join("\n")

                        if (env.TAG_NAME != null) {
                            if (!env.TAG_NAME.endsWith(pyproject_version)) {
                                error "mismatch between git tag (${env.TAG_NAME}) and pyproject (${pyproject_version})"
                            }
                            echo "building version: ${version}"
                        }
                        // else {
                        //     if (tag_for_version_exists == 0 ) {
                        //         error "git tag already exists for ${pyproject_version}, please bump the version number"
                        //     }
                        //     version = "${pyproject_version}.dev"
                        //     echo "building dev version: ${version}"
                        // }
                    }
                }
            } // 'version'

            stage('build') {
                steps {
                    // build the builder image with latest
                    sh "docker build --pull --target builder --build-arg PIP_REPO_NAME=gitea --build-arg PIP_REPO_URL=${config.pip_repo_url} -t ${repo}_builder:latest ."

                    sh "docker tag ${repo}_builder:latest ${repo}_builder:${version}"
                    sh "docker container create --name artifacts-${repo}-${env.BUILD_NUMBER} ${repo}_builder:${version}"
                    sh "docker container cp artifacts-${repo}-${env.BUILD_NUMBER}:/opt/${repo}/dist ."
                    script {
                        sh 'ls -1 dist/'
                        debfiles = findFiles(glob: 'dist/*.deb')
                    }
                }
            } // 'build'

            stage('docker image') {
                steps {
                    sh "docker build --pull -t ${docker_image_name}:latest ."
                    sh "docker tag ${docker_image_name}:latest ${docker_image_name}:${version}"
                }
            } // 'docker image'

            stage('docker push') {
                when {
                    expression { config.docker == true }
                }
                steps {
                    sh "docker push ${docker_image_name}:latest"
                    sh "docker push ${docker_image_name}:${version}"
                    echo "docker push"
                }
            } // 'docker push'

            stage('poetry publish') {
                when {
                    expression { config.pip_publish == true }
                    anyOf {
                        tag "v*"
                        allOf {
                            expression { pip_publish_tags_only == false }
                            expression { new_version_commit == true }
                            expression { artifacts_exist == false }
                        }
                    }
                }
                steps {
                    // this does not seem to work:
                    // --skip-existing: Ignore errors from files already existing in the repository.
                    withCredentials([string(credentialsId: 'gitea-user-token', variable: "POETRY_PYPI_TOKEN_GITEA")]) {
                        sh "docker run --rm ${repo}_builder:latest config repositories"
                        sh "docker run --rm -e POETRY_PYPI_TOKEN_GITEA ${repo}_builder:latest publish -r gitea"
                    }
                }
            } // 'poetry publish'

            stage('update apt repo') {
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
            } // 'update apt repo'
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
