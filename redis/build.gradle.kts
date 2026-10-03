import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    kotlin("jvm")
    `maven-publish`
}

group = rootProject.group
base.archivesName = "better-auth-kt-client-redis"
version = rootProject.version

repositories {
    mavenCentral()
}

dependencies {
    api(project(":"))
    // Lettuce talks to Redis and Valkey (same RESP protocol), standalone, Sentinel and Cluster.
    api("io.lettuce:lettuce-core:7.8.0.RELEASE")

    testImplementation(kotlin("test"))
    testImplementation("io.ktor:ktor-client-mock:3.6.0")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.11.0")
}

val jdkToolchain = providers.gradleProperty("jdkToolchain").map(String::toInt).getOrElse(25)

kotlin {
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
            artifactId = "better-auth-kt-client-redis"
        }
    }
}
