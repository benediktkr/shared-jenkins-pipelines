package is.sudo.jenkins

import com.cloudbees.groovy.cps.NonCPS

class Utils {
    @NonCPS
    static String default_or_value(String value, String defval) {
        if (value == null) {
            return defval
        }
        return value
    }

    @NonCPS
    static String dockerhub_account(String account) {
        if (account == null) {
            return "benediktkr"
        }
        return account
    }

    @NonCPS
    static String docker_image_name(String project) {
        return Utils.docker_image_name(project, null)
    }

    @NonCPS
    static String trim_docker_postfix(String repo) {
        if (repo.endsWith("-docker")) {
            return repo.substring(0, repo.indexOf("-docker"))
        }
        return repo
    }

    @NonCPS
    static String docker_image_name(String repo,  String account) {

        def imgname = Utils.trim_docker_postfix(repo)
        def acc = Utils.dockerhub_account(account)
        return "${acc}/${imgname}"
    }
}
