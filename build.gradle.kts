subprojects {
    group = providers.gradleProperty("group").get()
    version = providers.gradleProperty("version").get()

    plugins.withId("maven-publish") {
        configure<PublishingExtension> {
            repositories {
                maven {
                    name = "GitHubPackages"
                    url = uri("https://maven.pkg.github.com/true-sim/build-logic")
                    credentials {
                        username = providers.environmentVariable("GITHUB_ACTOR")
                            .orElse(providers.gradleProperty("gpr.user")).orNull
                        password = providers.environmentVariable("GITHUB_TOKEN")
                            .orElse(providers.gradleProperty("gpr.key")).orNull
                    }
                }
            }
        }
    }
}
