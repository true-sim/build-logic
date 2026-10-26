// Publishes the project's Java component to the repo's GitHub Packages registry.
// Credentials: GITHUB_ACTOR/GITHUB_TOKEN env vars in CI, gpr.user/gpr.key Gradle properties locally.
plugins {
    `maven-publish`
}

val repoName = providers.gradleProperty("truesim.github.repo").orElse(rootProject.name)

publishing {
    repositories {
        maven {
            name = "GitHubPackages"
            url = uri(repoName.map { "https://maven.pkg.github.com/true-sim/$it" }.get())
            credentials {
                username = providers.environmentVariable("GITHUB_ACTOR")
                    .orElse(providers.gradleProperty("gpr.user")).orNull
                password = providers.environmentVariable("GITHUB_TOKEN")
                    .orElse(providers.gradleProperty("gpr.key")).orNull
            }
        }
    }
}

plugins.withId("java") {
    publishing {
        publications {
            register<MavenPublication>("maven") {
                from(components["java"])
            }
        }
    }
}
