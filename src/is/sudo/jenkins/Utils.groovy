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
    static Boolean default_or_value(Boolean value, Boolean defval) {
        if (value == null) {
            return defval
        }
        return value
    }

    @NonCPS
    static String trim_docker_postfix(String repo) {
        if (repo.endsWith("-docker")) {
            return repo.substring(0, repo.indexOf("-docker"))
        }
        return repo
    }

    @NonCPS
    static String docker_image_name(String repo) {
        return Utils.docker_image_name(repo, null)
    }
    @NonCPS
    static String docker_image_name(String repo, String dockreg) {
        def trimmed_name = Utils.trim_docker_postfix(repo)
        def dockreg_url  = Utils.default_or_value(dockreg, "git.sudo.is/ops")
        return "${dockreg_url}/${trimmed_name}"
    }
}
