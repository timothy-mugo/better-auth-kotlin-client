import org.jreleaser.model.Active

plugins {
    base // JReleaser's tasks expect a `clean` task
    id("org.jreleaser") version "1.26.0"
}

// Shared with the library build: ../gradle.properties (libraryVersion, githubOwner, githubRepo, projectDescription).
val shared = java.util.Properties().apply { file("../gradle.properties").inputStream().use(::load) }
fun shared(key: String): String =
    providers.gradleProperty(key).orNull ?: shared.getProperty(key) ?: error("$key is missing in gradle.properties")

version = shared("libraryVersion")

// What a release does: creates the git tag v<version>, writes the changelog from the commit messages (conventional commits
// are grouped; others are listed too), creates the GitHub Release and attaches the staged jars and AARs plus checksums.
// JitPack then builds the tag for consumers. Run by the Release workflow; see README, "Releasing".
//
//   ./gradlew publish                                                              # stages artifacts into release/build/staging-deploy
//   JRELEASER_GITHUB_TOKEN=... ./gradlew -p release jreleaserRelease --dryrun      # shows what would happen, changes nothing
jreleaser {
    gitRootSearch = true
    project {
        name = "better-auth-kt-client"
        description = shared("projectDescription")
        authors.add("Timothy Mugo Gachengo")
        license = "Apache-2.0"
        inceptionYear = "2026"
        copyright = "2026 Timothy Mugo Gachengo"
        links {
            homepage = "https://github.com/${shared("githubOwner")}/${shared("githubRepo")}"
        }
    }
    release {
        github {
            repoOwner = shared("githubOwner")
            name = shared("githubRepo")
            branch = "main"
            tagName = "v{{projectVersion}}"
            // The tag may already exist after a half-finished run; the workflow passes -PskipTag=true then.
            skipTag = providers.gradleProperty("skipTag").map(String::toBoolean).orElse(false)
            overwrite = false
            prerelease {
                enabled = providers.provider { version.toString().contains('-') }
            }
            changelog {
                formatted = Active.ALWAYS
                preset = "conventional-commits"
                contributors {
                    enabled = false
                }
            }
        }
    }
    files {
        active = Active.ALWAYS
        glob {
            // relative to this build; `./gradlew publish` in the library build stages the artifacts here
            pattern = "build/staging-deploy/**/*.{jar,aar}"
        }
    }
}
