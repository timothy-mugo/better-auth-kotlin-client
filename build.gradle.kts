import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    kotlin("jvm") version "2.4.10"
    kotlin("plugin.serialization") version "2.4.10"
    `maven-publish`
    // Declared here so every module shares one plugin classpath; applied only by :android.
    id("com.android.library") version "9.0.1" apply false
}

// Coordinates. Outside JitPack: group com.timothymugo and the version from gradle.properties (libraryVersion; override with
// -PlibraryVersion=1.2.3). On JitPack the group must be com.github.<user> and the version is the tag being built (see
// jitpack.yml).
val onJitPack = System.getenv("JITPACK") == "true"
group = if (onJitPack) System.getenv("GROUP") else "com.timothymugo"
version = if (onJitPack) System.getenv("VERSION") else providers.gradleProperty("libraryVersion").get()

// JDK used to compile. The output is JVM 17 bytecode regardless (see below); CI and JitPack can pass -PjdkToolchain=21.
val jdkToolchain = providers.gradleProperty("jdkToolchain").map(String::toInt).getOrElse(25)

repositories {
    mavenCentral()
}

val ktorVersion = "3.6.0"
val coroutinesVersion = "1.11.0"
val serializationVersion = "1.11.0"
val okhttpVersion = "5.3.2"

dependencies {
    api("io.ktor:ktor-client-core:$ktorVersion")
    api("org.jetbrains.kotlinx:kotlinx-serialization-json:$serializationVersion")
    api("org.jetbrains.kotlinx:kotlinx-coroutines-core:$coroutinesVersion")
    // Ktor 3.6 pulls OkHttp 5.5, whose Android artifact demands compileSdk 37 from every app that depends on this
    // library (OkHttp 5.4 demands 36; up to 5.3.x demand nothing). Pin the last unconstrained release, non-strictly,
    // so an app that wants a newer OkHttp can still raise it.
    implementation("io.ktor:ktor-client-okhttp:$ktorVersion") {
        exclude(group = "com.squareup.okhttp3", module = "okhttp")
    }
    implementation("com.squareup.okhttp3:okhttp:$okhttpVersion")

    testImplementation(kotlin("test"))
    testImplementation("io.ktor:ktor-client-mock:$ktorVersion")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:$coroutinesVersion")
}

kotlin {
    // Compile with the JDK 25 toolchain but emit JVM 17 bytecode so the library loads on
    // Android (AGP) and on Java 17+ backends.
    jvmToolchain(jdkToolchain)
    explicitApi()
    compilerOptions {
        jvmTarget = JvmTarget.JVM_17
        freeCompilerArgs.add("-Xjdk-release=17")
    }
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
    withSourcesJar()
}

tasks.test {
    useJUnitPlatform()
}

publishing {
    publications {
        create<MavenPublication>("maven") {
            from(components["java"])
            artifactId = "better-auth-kotlin-client"
        }
    }
}

// --- Publishing metadata, shared by every module ------------------------------------------------------------------------
val ghOwner = providers.gradleProperty("githubOwner").get()
val ghRepo = providers.gradleProperty("githubRepo").get()
val projectDescription = providers.gradleProperty("projectDescription").get()

allprojects {
    plugins.withId("maven-publish") {
        configure<PublishingExtension> {
            publications.withType<MavenPublication>().configureEach {
                pom {
                    name.set(providers.provider { artifactId })
                    description.set(projectDescription)
                    url.set("https://github.com/$ghOwner/$ghRepo")
                    inceptionYear.set("2026")
                    licenses {
                        license {
                            name.set("The Apache License, Version 2.0")
                            url.set("https://www.apache.org/licenses/LICENSE-2.0.txt")
                        }
                    }
                    developers {
                        developer {
                            id.set(ghOwner)
                            name.set("Timothy Mugo Gachengo")
                        }
                    }
                    scm {
                        connection.set("scm:git:https://github.com/$ghOwner/$ghRepo.git")
                        developerConnection.set("scm:git:ssh://git@github.com/$ghOwner/$ghRepo.git")
                        url.set("https://github.com/$ghOwner/$ghRepo")
                    }
                }
            }
            repositories {
                // `./gradlew publish` stages every module here, inside release/ where JReleaser (a separate build) attaches the files to the
                // GitHub Release.
                maven {
                    name = "staging"
                    url = rootProject.layout.projectDirectory.dir("release/build/staging-deploy").asFile.toURI()
                }
            }
        }
    }
}
